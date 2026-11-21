package top.syshub.accountsx.common.auth.yggdrasil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorAuthException;
import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.AvatarService;
import top.syshub.accountsx.common.utils.Threading;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.oauth.OAuthEndpoints;
import top.syshub.accountsx.common.auth.oauth.IdTokenVerifier;

/**
 * Yggdrasil device-code（OAuth）流程（P3.1：从 {@code AbstractInjectorAccountProvider} 拆出）。
 * 端点与 clientId 取自注册表条目；条目缺字段时才回退到"GET 根 + openid-configuration"探测
 * （旧启动器写的 servers[] 没有 device 端点、或启动预热时服务器宕过机）。
 *
 * <p>流程拆成两段以支撑「先展示验证码、用户完成授权后再开局」的界面：
 * {@link #requestDeviceCode} 只换设备码，{@link #awaitToken} 负责轮询 token；界面自己决定
 * 展示验证码、开浏览器、复制链接这些副作用，这一段不再需要 {@code Prompts} 抽象。</p>
 */
public final class YggdrasilDeviceCodeFlow {

    /** 休眠抽象：单测要快进、界面要能在关闭后立刻放弃，都不能依赖裸 {@link Thread#sleep}。 */
    public interface Sleep {
        void sleep(long millis) throws IOException;
    }

    /**
     * 两阶段登录的中间结果。
     *
     * <p>端点与 clientId 一起带上，轮询阶段因此不必回查注册表，也不会在两次请求之间被注册表更新影响。</p>
     */
    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
            String verificationUriComplete, int interval, int expiresIn,
            String clientId, String tokenEndpoint) {

        /** 优先用带 code 的完整地址，用户不必再手工输入。 */
        public String browserUrl() {
            return verificationUriComplete != null ? verificationUriComplete : verificationUri;
        }
    }

    private static final String SCOPE =
            "openid offline_access Yggdrasil.PlayerProfiles.Select Yggdrasil.Server.Join";

    /** 过期时刻的安全余量（毫秒）：与微软侧一致，提前 60 秒视为过期，边界上宁可多刷一次。 */
    private static final long EXPIRY_SKEW_MILLIS = 60_000L;

    private YggdrasilDeviceCodeFlow() {
    }

    /** 缓存端点是否足以驱动 device-code：缺 device / token 端点即视为该服务器不支持 OAuth。 */
    public static boolean supportsDeviceCode(AuthServer server) {
        OAuthEndpoints endpoints = server.oauth();
        return endpoints != null && endpoints.clientId() != null && endpoints.tokenEndpoint() != null
                && endpoints.deviceAuthorizationEndpoint() != null;
    }

    /** 第一阶段：只换设备码，不做任何端点探测（缺端点直接失败，由界面退回密码登录）。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static DeviceCode requestDeviceCode(HttpGateway http, AuthServer server) throws IOException {
        if (!supportsDeviceCode(server))
            throw new IOException("This server does not support OAuth login.");
        OAuthEndpoints endpoints = server.oauth();

        JsonObject device = http.postForm(endpoints.deviceAuthorizationEndpoint(),
                Map.of("client_id", endpoints.clientId(), "scope", SCOPE));
        return new DeviceCode(
                requireString(device, "device_code"),
                requireString(device, "user_code"),
                str(device.get("verification_uri")),
                str(device.get("verification_uri_complete")),
                Math.max(intOr(device.get("interval"), 5), 1),
                Math.max(intOr(device.get("expires_in"), 300), 1),
                endpoints.clientId(),
                endpoints.tokenEndpoint());
    }

    /**
     * 第二阶段：轮询 token 端点直到用户完成授权。
     *
     * <p>{@code cancelled} 为真（用户关掉了登录界面）时抛 {@link IOException}——调用方已经把界面
     * 判为失效，迟到的结果本来就会被丢弃，这里只是让轮询尽快停下来。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult awaitToken(HttpGateway http, AuthServer server, DeviceCode code,
            Sleep sleep, BooleanSupplier cancelled) throws IOException {
        long stepMillis = Math.max(code.interval(), 1) * 1000L;
        long deadline = System.nanoTime() + Math.max(code.expiresIn(), 1) * 1_000_000_000L;
        String accessToken = null, refreshToken = null, idToken = null;
        JsonObject tokenResponse = null;

        while (System.nanoTime() < deadline) {
            sleep.sleep(stepMillis);
            if (cancelled.getAsBoolean())
                throw new IOException("Cancelled.");

            JsonObject token = http.postForm(code.tokenEndpoint(), Map.of(
                    "client_id", code.clientId(),
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", code.deviceCode()), true);

            JsonElement err = token.get("error");
            if (err == null || err.isJsonNull()) {
                // 三个令牌缺一不可：少了 refresh_token 等于「一次能用」的账号，少了 id_token 就
                // 不知道这个令牌属于哪个角色，都不能当成功处理。
                validateTokens(token);
                accessToken = requireString(token, "access_token");
                refreshToken = requireString(token, "refresh_token");
                idToken = requireString(token, "id_token");
                tokenResponse = token;
                break;
            }

            String error = str(err);
            if (error == null)
                throw new IOException("Token endpoint returned an unreadable error.");
            if (error.equals("authorization_pending") || error.equals("slow_down"))
                continue;
            if (error.equals("access_denied"))
                throw new IOException("Authorization denied.");
            if (error.equals("expired_token"))
                throw new IOException("No character detected.");
            throw new IOException("Unknown error: " + error);
        }

        if (accessToken == null)
            throw new IOException("Timed out waiting for authorization.");

        return toResult(http, server, tokenResponse, accessToken, refreshToken, idToken,
                code.tokenEndpoint(), code.clientId());
    }

    /** 可被打断的休眠：按 200 ms 切片检查取消标志，关窗后不必等满一个 interval 才停。 */
    public static Sleep cancellableSleep(BooleanSupplier cancelled) {
        return millis -> {
            long remaining = millis;
            while (remaining > 0) {
                if (cancelled.getAsBoolean())
                    throw new IOException("Cancelled.");
                long slice = Math.min(remaining, 200L);
                try {
                    Thread.sleep(slice);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted.", e);
                }
                remaining -= slice;
            }
        };
    }

    /**
     * 用 userinfo 端点确认 OAuth 访问令牌仍有效（issue 268：授权服务器回 401 即无效）。
     *
     * <p>OIDC 访问令牌不是 Yggdrasil 会话令牌，/authserver/validate 不认识它；
     * userinfo 端点就是规范规定的令牌校验入口（GET + Bearer）。结论分三档，判据与
     * {@link YggdrasilPasswordFlow#validate} 相同：2xx → {@link TokenReuse#YES}；
     * 401/403 等 4xx → {@link TokenReuse#NO}；5xx / 429 或网关无法给出状态码 →
     * {@link TokenReuse#UNKNOWN}。端点未知时无从询问，同样按「问不出来」处理，绝不据此刷新。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static TokenReuse validateAccessToken(HttpGateway http, AuthServer server, String accessToken)
            throws IOException {
        OAuthEndpoints oauth = server.oauth();
        String userinfo = oauth == null ? null : oauth.userinfoEndpoint();
        if (userinfo == null || userinfo.isBlank())
            return TokenReuse.UNKNOWN;
        HttpGateway.Result result;
        try {
            result = http.getWithStatus(userinfo, Map.of("Authorization", "Bearer " + accessToken));
        } catch (UnsupportedOperationException e) {
            return TokenReuse.UNKNOWN;
        }
        return YggdrasilPasswordFlow.classify(result);
    }

    /** OAuth 刷新（信封 JSON 即 loginToken 去掉前缀的部分）。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult refresh(HttpGateway http, String envelopeJson, AuthServer server)
            throws IOException {
        JsonObject envelope = JsonParser.parseString(envelopeJson).getAsJsonObject();
        String tokenEndpoint = envelope.get("token_endpoint").getAsString();
        String refreshToken = envelope.get("refresh_token").getAsString();
        String clientId = envelope.get("client_id").getAsString();

        Map<String, String> form = Map.of(
                "client_id", clientId,
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
        );
        // 不校验状态码：RFC 6749 的错误体（400/403 + {error, error_description}）比裸状态码有用得多，
        // 旧实现按状态码抛 IOException，错误原因就只剩「HTTP 400」。
        JsonObject token = http.postForm(tokenEndpoint, form, true);
        throwIfRejected(token);
        validateTokens(token);
        String accessToken = requireString(token, "access_token");
        String idToken = requireString(token, "id_token");
        // refresh_token 只在申请了 offline_access 时才「必须」，服务端可以不轮换：
        // 不返回就沿用信封里的旧值，否则一次成功刷新会把账号变成再也刷不动的死账号。
        String rotated = optionalString(token, "refresh_token");

        return toResult(http, server, token, accessToken, rotated != null ? rotated : refreshToken, idToken,
                tokenEndpoint, clientId);
    }

    /** token 响应 → 账号数据：id_token 给身份（先验签与声明），sessionserver 给头像，expires_in 给过期时刻。 */
    private static YggdrasilAuthResult toResult(HttpGateway http, AuthServer server, JsonObject token,
            String accessToken, String refreshToken, String idToken, String tokenEndpoint, String clientId) throws IOException {
        YggdrasilProfiles.Profile profile = readIdTokenProfile(http, server, idToken);
        AvatarService.AvatarKey avatar = AvatarService.fetch(http,
                server.apiRoot() + "/sessionserver/session/minecraft/profile/", profile.playerUuid());
        return new YggdrasilAuthResult(accessToken, profile.playerName(), profile.playerUuid(),
                refreshToken, tokenEndpoint, clientId,
                // OAuth 账号走 OAuth 刷新端点，不使用 Yggdrasil clientToken。
                null,
                avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt(),
                expiresAtOf(token));
    }

    /**
     * token 响应的 {@code expires_in}（OIDC 必具）→ 过期时刻（epoch 毫秒）。
     * 缺失或类型不对按未知（0）处理：显式的非正数已被 {@code validateTokens} 拒掉，这里只兜解析之外的情形，
     * 宁可下次启动多走一次 userinfo 校验，也不要把过期令牌当成可复用。
     */
    private static long expiresAtOf(JsonObject token) {
        JsonElement expiresIn = token.get("expires_in");
        if (!(expiresIn instanceof JsonPrimitive p) || !p.isNumber())
            return 0L;
        long seconds = p.getAsLong();
        return seconds <= 0 ? 0L : System.currentTimeMillis() + seconds * 1000L - EXPIRY_SKEW_MILLIS;
    }

    /**
     * 从 id_token 取角色：校验签名与 iss/aud/iat/exp 之后才读 claims。
     *
     * <p>校验失败即抛 {@link IOException}——未经验证的令牌不能决定「我登录的是谁」。</p>
     */
    static YggdrasilProfiles.Profile readIdTokenProfile(HttpGateway http, AuthServer server, String idToken)
            throws IOException {
        JsonObject claims = IdTokenVerifier.verify(http, server, idToken);
        List<YggdrasilProfiles.Profile> profiles = YggdrasilProfiles.readProfiles(claims);
        if (profiles.isEmpty())
            throw new IOException("id token carries no profile.");
        return profiles.get(0);
    }

    /**
     * 端点错误体（RFC 6749 §5.2）：{@code error} / {@code error_description}。
     *
     * <p>消息里只带错误码与描述，绝不拼整个响应体——设备码轮询的响应里可能带着令牌。</p>
     */
    private static void throwIfRejected(JsonObject token) throws IOException {
        JsonElement err = token.get("error");
        if (err == null || err.isJsonNull())
            return;
        String error = str(err);
        if (error == null)
            throw new IOException("Token endpoint returned an unreadable error.");
        String description = str(token.get("error_description"));
        // 只映射「重新添加账号确实是解药」的那两个错误码：invalid_grant / invalid_token 表示
        // 服务端不认这份凭据（refresh_token 被用过、被撤销或已过期）。其余错误码各有用因，
        // 提示用户去删账号只会让他白删一次，宁可退回「未知错误」。
        String key = switch (error) {
            case "invalid_grant", "invalid_token" -> "accountsx.account.fail.injector_invalid_token";
            default -> null;
        };
        String message = "Token endpoint rejected the request: " + error
                + (description == null ? "" : " (" + description + ")");
        if (key == null)
            throw new IOException(message);
        throw new InjectorAuthException(key, message);
    }

    /** 校验 token 响应里能校验的元信息：{@code token_type} 必须是 Bearer，{@code expires_in} 必须为正。 */
    private static void validateTokens(JsonObject token) throws IOException {
        String tokenType = str(token.get("token_type"));
        if (tokenType != null && !"Bearer".equalsIgnoreCase(tokenType))
            throw new IOException("Unexpected token_type: " + tokenType);
        JsonElement expiresIn = token.get("expires_in");
        if (expiresIn instanceof JsonPrimitive p && p.isNumber() && p.getAsLong() <= 0)
            throw new IOException("Token response declares a non-positive expires_in: " + p.getAsString());
    }

    /** 必需的字符串字段；缺失或类型不对时报出字段名与本响应出现过哪些字段，便于对着服务端排查。 */
    private static String requireString(JsonObject json, String field) throws IOException {
        String value = optionalString(json, field);
        if (value == null)
            throw new IOException("Response does not contain a string " + field + "; fields=" + json.keySet());
        return value;
    }

    private static String optionalString(JsonObject json, String field) {
        String value = str(json.get(field));
        return value == null || value.isBlank() ? null : value;
    }

    private static int intOr(JsonElement e, int fallback) {
        return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsInt() : fallback;
    }

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }
}
