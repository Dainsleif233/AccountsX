package top.syshub.accountsx.common.accounts.impl.microsoft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.accounts.model.context.AuthPolicy;
import top.syshub.accountsx.common.net.HttpGateway;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import static top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftConstants.SESSION;
import top.syshub.accountsx.common.utils.AvatarService;

public class MicrosoftAccountProvider implements AccountProvider<MicrosoftAccount> {
    private static final String SCOPE = "XboxLive.signin offline_access";

    private static final String CLIENT_ID = "bcc75d9d-4d01-408a-ba7b-131e955e70f1";

    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";

    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    /** 过期判定的安全余量：提前 60 秒视为过期，避免「刚好在过期瞬间」拿旧令牌进游戏。 */
    private static final long EXPIRY_SKEW_MS = 60_000L;

    private final HttpGateway http;

    @Override
    public AccountContext createAccountContext(MicrosoftAccount account) throws IOException {
        return new AccountContext(MicrosoftConstants.SERVER_CONTEXT, MicrosoftConstants.computeMicrosoftPublicKeys(http), AuthPolicy.ONLINE);
    }

    public MicrosoftAccountProvider(HttpGateway http) {
        this.http = http;
    }

    /** 设备码一次性的取码结果：UI 负责展示与打开浏览器。 */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri, int interval, int expiresIn) {
    }

    /** 取设备码（不含轮询）；失败直接抛 IOException 由调用方展示。 */
    public DeviceCode requestDeviceCode() throws IOException {
        JsonObject device = http.postForm(DEVICE_CODE_URL, Map.of(
                "client_id", CLIENT_ID,
                "scope", SCOPE
        ));

        int interval;
        if (device.get("interval") instanceof JsonPrimitive jp && jp.isNumber()) {
            interval = jp.getAsInt();
        } else {
            interval = 5;
        }
        int expires;
        if (device.get("expires_in") instanceof JsonPrimitive jp && jp.isNumber()) {
            expires = jp.getAsInt();
        } else {
            expires = 300;
        }
        return new DeviceCode(device.get("device_code").getAsString(), device.get("user_code").getAsString(),
                device.get("verification_uri").getAsString(), Math.max(interval, 1), Math.max(expires, 1));
    }

    /**
     * 轮询设备码并完成 Xbox → Minecraft 链路。
     *
     * <p>{@code cancelled} 为 true 时尽快放弃：界面关窗要能立刻停掉轮询，而不是等满分钟级的授权窗口。</p>
     */
    public MicrosoftAccount loginDeviceCode(DeviceCode device, BooleanSupplier cancelled) throws IOException, CancellationException {
        String microsoftAccessToken = null, microsoftRefreshToken = null;

        int interval = device.interval();
        long deadline = System.nanoTime() + device.expiresIn() * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted.", e);
            }

            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Screen has been closed.");
            }

            JsonObject token = http.postForm(TOKEN_URL, Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "code", device.deviceCode(),
                    "client_id", CLIENT_ID
            ), true);

            JsonElement err = token.get("error");
            if (err == null || err.isJsonNull()) {
                microsoftAccessToken = token.get("access_token").getAsString();
                microsoftRefreshToken = token.get("refresh_token").getAsString();
                break;
            }

            // error 不是字符串时按不可读处理，而不是让 getAsString() 炸出 UnsupportedOperationException。
            String error = err instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
            if (error == null)
                throw new IOException("Token endpoint returned an unreadable error.");

            if ("authorization_pending".equals(error)) {
                continue;
            }

            if ("expired_token".equals(error)) {
                throw new IOException("No character detected.");
            }

            if ("slow_down".equals(error)) {
                interval += 5;
                continue;
            }

            throw new IOException("Unknown error: " + error);
        }

        if (microsoftAccessToken == null || microsoftRefreshToken == null) throw new IOException("Invalid token.");

        return finishLogin(microsoftAccessToken, microsoftRefreshToken);
    }

    /** token 到手后的公共尾巴：Xbox Live → XSTS → Minecraft → profile / avatar。 */
    private MicrosoftAccount finishLogin(String microsoftAccessToken, String microsoftRefreshToken) throws IOException {

        String xblToken, userHash;
        {
            JsonObject properties = new JsonObject();
            properties.addProperty("AuthMethod", "RPS");
            properties.addProperty("SiteName", "user.auth.xboxlive.com");
            properties.addProperty("RpsTicket", "d=" + microsoftAccessToken);

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "http://auth.xboxlive.com");
            root.addProperty("TokenType", "JWT");

            JsonObject json = http.postJson("https://user.auth.xboxlive.com/user/authenticate", root);
            xblToken = json.get("Token").getAsString();
            userHash = json.get("DisplayClaims").getAsJsonObject().get("xui").getAsJsonArray().get(0).getAsJsonObject().get("uhs").getAsString();
        }

        String xstsToken;
        {
            JsonArray tokens = new JsonArray();
            tokens.add(xblToken);

            JsonObject properties = new JsonObject();
            properties.addProperty("SandboxId", "RETAIL");
            properties.add("UserTokens", tokens);

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
            root.addProperty("TokenType", "JWT");

            xstsToken = http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", root).get("Token").getAsString();
        }

        String accessToken;
        long expiresAt;
        {
            JsonObject root = new JsonObject();
            root.addProperty("identityToken", String.format("XBL3.0 x=%s;%s", userHash, xstsToken));

            JsonObject json = http.postJson(MicrosoftConstants.MS_LOGIN_XBOX, root);
            accessToken = json.get("access_token").getAsString();
            expiresAt = expiryOf(json);
        }

        String playerName, playerUUID;
        {
            JsonObject json = http.get(MicrosoftConstants.MS_GAME_PROFILE, Map.of(
                    "Authorization", "Bearer " + accessToken
            ));

            if (json.has("error"))
                throw new IOException("Failed to get UUID");

            playerName = json.get("name").getAsString();
            playerUUID = json.get("id").getAsString();
        }

        AvatarService.AvatarKey avatar = AvatarService.fetch(http, SESSION + "/session/minecraft/profile/", playerUUID);
        return new MicrosoftAccount(
                accessToken,
                playerName,
                AccountUUID.parse(playerUUID),
                microsoftAccessToken,
                microsoftRefreshToken,
                avatar == null ? null : avatar.key(),
                avatar == null ? 0L : avatar.cachedAt(),
                expiresAt
        );
    }

    /**
     * 从 token 响应里算出过期时刻（epoch 毫秒），0 表示未知。
     *
     * <p>字段缺失或不是正数时一律当未知——宁可下次启动完整刷新一次，也不要因为解析问题
     * 把已过期的令牌当成可复用。</p>
     */
    private static long expiryOf(JsonObject json) {
        JsonElement expiresIn = json.get("expires_in");
        if (expiresIn == null || !expiresIn.isJsonPrimitive() || !expiresIn.getAsJsonPrimitive().isNumber())
            return 0L;
        long seconds = expiresIn.getAsLong();
        return seconds <= 0 ? 0L : System.currentTimeMillis() + seconds * 1000L - EXPIRY_SKEW_MS;
    }

    @Override
    public void refresh(MicrosoftAccount account) throws IOException {
        {
            // ignoreHttpStatus=true：RFC 6749 §5.2 的错误体（400/401 + {error, error_description}）
            // 比裸「HTTP 400」有用得多，invalid_grant 时用户能知道是令牌被吊销而不是网络坏了。
            JsonObject token = http.postForm(TOKEN_URL, Map.of(
                    "client_id", CLIENT_ID,
                    "refresh_token", account.getMicrosoftAccountRefreshToken(),
                    "grant_type", "refresh_token"
            ), true);
            throwIfRejected(token);

            account.setMicrosoftAccountToken(
                    token.get("access_token").getAsString(),
                    token.get("refresh_token").getAsString()
            );
        }

        String xblToken, userHash;
        {
            JsonObject properties = new JsonObject();
            properties.addProperty("AuthMethod", "RPS");
            properties.addProperty("SiteName", "user.auth.xboxlive.com");
            properties.addProperty("RpsTicket", "d=" + account.getMicrosoftAccountAccessToken());

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "http://auth.xboxlive.com");
            root.addProperty("TokenType", "JWT");

            JsonObject json = http.postJson("https://user.auth.xboxlive.com/user/authenticate", root);
            xblToken = json.get("Token").getAsString();
            userHash = json.get("DisplayClaims").getAsJsonObject().get("xui").getAsJsonArray().get(0).getAsJsonObject().get("uhs").getAsString();
        }

        String xstsToken;
        {
            JsonArray tokens = new JsonArray();
            tokens.add(xblToken);

            JsonObject properties = new JsonObject();
            properties.addProperty("SandboxId", "RETAIL");
            properties.add("UserTokens", tokens);

            JsonObject root = new JsonObject();
            root.add("Properties", properties);
            root.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
            root.addProperty("TokenType", "JWT");

            xstsToken = http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", root).get("Token").getAsString();
        }

        String accessToken;
        long expiresAt;
        {
            JsonObject root = new JsonObject();
            root.addProperty("identityToken", String.format("XBL3.0 x=%s;%s", userHash, xstsToken));

            JsonObject json = http.postJson(MicrosoftConstants.MS_LOGIN_XBOX, root);
            accessToken = json.get("access_token").getAsString();
            expiresAt = expiryOf(json);
        }

        String playerName, playerUUID;
        {
            JsonObject json = http.get(MicrosoftConstants.MS_GAME_PROFILE, Map.of(
                    "Authorization", "Bearer " + accessToken
            ));

            if (json.has("error"))
                throw new IOException("Failed to get UUID");

            playerName = json.get("name").getAsString();
            playerUUID = json.get("id").getAsString();
        }

        account.setProfile(accessToken, playerName, AccountUUID.parse(playerUUID), expiresAt);
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, SESSION + "/session/minecraft/profile/", playerUUID);
        account.setAvatar(avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }

    /**
     * 令牌端点错误体（RFC 6749 §5.2）：{@code error} / {@code error_description}。
     * 消息里只带这两项，绝不拼整个响应体——错误响应之外的字段可能带着令牌。
     */
    private static void throwIfRejected(JsonObject token) throws IOException {
        JsonElement err = token.get("error");
        if (err == null || err.isJsonNull())
            return;
        String error = err instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
        if (error == null)
            throw new IOException("Token endpoint returned an unreadable error.");
        JsonElement desc = token.get("error_description");
        String description = desc instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
        throw new IOException("Token endpoint rejected the request: " + error
                + (description == null ? "" : " (" + description + ")"));
    }

    /**
     * 微软的 MC accessToken 自带 {@code expires_in}，到期前可以直接复用，省掉整条
     * MSA → Xbox Live → XSTS → Minecraft 换票（五次请求）。
     *
     * <p>过期时刻未知（0）时返回 {@code false}：宁可多刷新一次，也不要把死令牌带进游戏。</p>
     */
    @Override
    public boolean canReuseToken(MicrosoftAccount account) {
        return account.getAccountStorage().isTokenKnownUnexpired();
    }

    /**
     * 微软没有公开的单令牌撤销端点（注销需要用户在账号页面交互式移除应用授权），
     * 因此删除账号只清本地凭据，服务端令牌靠自然过期。
     */
    @Override
    public void signOut(MicrosoftAccount account) {
    }
}
