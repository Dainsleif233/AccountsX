package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.*;
import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.model.PlayerNoLongerExistedException;
import top.syshub.accountsx.common.accounts.model.context.*;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.ui.UIScreen;
import top.syshub.accountsx.common.utils.AvatarService;

import java.io.IOException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractInjectorAccountProvider<T extends AbstractInjectorAccount> implements AccountProvider<T> {
    private static final String GUID_SERVER_BASE = "guid:as.login.injector.widgets.server_url";
    private static final String GUID_USER_NAME = "guid:as.login.injector.widgets.user_name";
    private static final String GUID_PASSWORD = "guid:as.login.injector.widgets.user_password";
    private static final String GUID_PLAYER_NAME = "guid:as.login.injector.widgets.player_name";

    private final String serverBaseTranslationKey;

    private final String userBaseTranslationKey;

    private final String accountContextName;

    protected final HttpGateway http;

    /**
     * 生成 clientToken 用。drasl 自己用 {@code RandomHex(16)} 生成 32 个十六进制字符
     * （drasl {@code util.go}），Yggdrasil 规范也把 clientToken 限制在 32 字符以内，
     * 因此这里采用相同形状，避免只对 drasl 有效。
     */
    private static final SecureRandom CLIENT_TOKEN_RANDOM = new SecureRandom();

    protected AbstractInjectorAccountProvider(String serverBaseTranslationKey, String userBaseTranslationKey, String accountContextName, HttpGateway http) {
        this.serverBaseTranslationKey = serverBaseTranslationKey;
        this.accountContextName = accountContextName;
        this.userBaseTranslationKey = userBaseTranslationKey;
        this.http = http;
    }

    protected void validateServerBaseURL(String server) throws IllegalArgumentException {}

    protected abstract String transformServerBaseURL(String server);

    protected abstract T createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt, String clientToken);

    /** 生成一个 32 字符十六进制 clientToken。 */
    private static String generateClientToken() {
        byte[] bytes = new byte[16];
        CLIENT_TOKEN_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 统一把服务端的 Yggdrasil 错误响应转成 {@link InjectorAuthException}。
     * 注意只暴露服务端返回的 error / errorMessage，绝不带上任何令牌。
     *
     * <p>对 {@code "error"} 做类型判断而非直接 {@code getAsString()}：不规范的服务端可能把它写成
     * {@code null} 或对象，此时 {@code getAsString()} 会抛 {@link UnsupportedOperationException}，
     * 让本可操作的提示退化成「未知错误」并丢掉错误本身。</p>
     *
     * <p>i18n key 按调用场景区分：登录失败多半是用户名/密码写错（此时还没有账号可删），
     * 而刷新失败才是「凭据已失效」——沿用同一条提示会给出错误的操作建议。</p>
     */
    private static void throwIfError(JsonObject json, String translationKey) throws InjectorAuthException {
        JsonElement error = json.get("error");
        if (error == null || error.isJsonNull()) {
            return;
        }

        JsonElement errorMessage = json.get("errorMessage");
        String detail = errorMessage != null && errorMessage.isJsonPrimitive() ? errorMessage.getAsString() : "";
        throw new InjectorAuthException(
                translationKey,
                "Cannot auth this injector: " + error + (detail.isEmpty() ? "" : " - " + detail)
        );
    }

    /**
     * 取响应里的 accessToken；缺失或类型不对时抛 {@link IOException} 而不是 NPE。
     *
     * <p>认证请求是忽略 HTTP 状态码发出的（否则读不到 403 的错误体），因此任何非 2xx 响应都可能
     * 流到这里。drasl 的 500 响应是 {@code {"path":…,"errorMessage":"internal server error"}} ——
     * 没有 {@code error} 字段，{@link #throwIfError} 会放行；若此时直接取值，抛出的未检查异常会
     * 绕过 {@code AccountManager.refreshAccount} 的 IOException 分支，账号永远停在「登录中」。</p>
     */
    private static String requireAccessToken(JsonObject json) throws IOException {
        JsonElement accessToken = json.get("accessToken");
        if (accessToken == null || !accessToken.isJsonPrimitive() || !accessToken.getAsJsonPrimitive().isString()) {
            // 只报告字段名，绝不把响应体拼进消息：该消息会进日志，而某些 Yggdrasil 实现的错误体
            // 会回显 accessToken/clientToken，等于把凭据写进日志（违反 AGENTS.md 的安全约束）。
            throw new IOException("Yggdrasil response does not contain a string accessToken; fields=" + json.keySet());
        }
        return accessToken.getAsString();
    }

    /**
     * 取服务端回显的 clientToken；缺省或为空时返回 {@code fallback}。
     *
     * <p>drasl 会把请求里的 clientToken 原样回显（{@code auth.go:271/393}），以服务端值为准
     * 可以对齐第三方实现可能做的规范化（例如补全/截断），避免下次刷新比对不上。</p>
     */
    private static String echoedClientToken(JsonObject json, String fallback) {
        JsonElement echoed = json.get("clientToken");
        if (echoed != null && echoed.isJsonPrimitive() && echoed.getAsJsonPrimitive().isString()) {
            String value = echoed.getAsString();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return fallback;
    }

    @Override
    public final AccountContext createAccountContext(T account) throws IOException {
        String url = account.getServer();

        List<PublicKey> publicKeys;
        List<String> skinDomains = new ArrayList<>();

        JsonObject response = http.get(url);
        if (response.get("signaturePublickey") instanceof JsonPrimitive jp && jp.isString()) {
            try {
                publicKeys = List.of(parseSignaturePublicKey(jp.getAsString()));
            } catch (final NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IOException("Invalid Yggdrasil public key!", e);
            }
        } else {
            throw new IOException("Invalid Yggdrasil public key!");
        }

        if (response.get("skinDomains") instanceof JsonArray ja) {
            for (JsonElement je : ja) {
                if (je instanceof JsonPrimitive domain && domain.isString()) {
                    skinDomains.add(domain.getAsString());
                } else {
                    throw new IOException("Invalid Yggdrasil public key!");
                }
            }
        } else {
            throw new IOException("Invalid Yggdrasil public key!");
        }

        // drasl 4.x 等实现会在元数据里声明 feature.enable_profile_key：
        // 它们的 minecraftservices 前缀下有真正的 player/certificates 端点，能签发
        // 被「服务端 authlib-injector 指向同一认证服务器」信任的档案密钥对签名。
        // 此时必须让 authlib 走真实请求，而不是让 mixin 注入假签名导致服务端以
        // 「无效的玩家档案公钥签名」踢人。缺省（含解析失败）保持 false → 旧回退行为。
        boolean profileKeySupported = false;
        if (response.get("meta") instanceof JsonObject meta
                && meta.get("feature.enable_profile_key") instanceof JsonPrimitive flag
                && flag.isBoolean()) {
            profileKeySupported = flag.getAsBoolean();
        }

        return new AccountContext(new AuthServerContext(
                url + "/authserver",
                url + "/api",
                url + "/sessionserver",
                url + "/minecraftservices",
                accountContextName,
                profileKeySupported
        ), new AuthSecurityContext(
                publicKeys, publicKeys,
                SkinURLVerifier.ofOperationOR(SkinURLVerifier.ofDomainVerifier(skinDomains, List.of()), SkinURLVerifier.MOJANG_DEFAULT)
        ), AuthPolicy.TRY);
    }

    private static PublicKey parseSignaturePublicKey(String pem) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        pem = pem.replace("\n", "").replace("\r", "");

        String header = "-----BEGIN PUBLIC KEY-----", end = "-----END PUBLIC KEY-----";
        if (!pem.startsWith(header) || !pem.endsWith(end)) {
            throw new IOException("Bad key format");
        }

        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(pem.substring(header.length(), pem.length() - end.length()))
        ));
    }

    @Override
    public final void configure(UIScreen screen) {
        screen.setTitle("accountsx.account.general.login");
        screen.putTextInput(GUID_SERVER_BASE, serverBaseTranslationKey);
        screen.putTextInput(GUID_USER_NAME, userBaseTranslationKey);
        screen.putTextInput(GUID_PASSWORD, "accountsx.account.objects.user_password");
        screen.putTextInput(GUID_PLAYER_NAME, "accountsx.account.objects.player_name");
    }

    @Override
    public final int validate(UIScreen screen, Memory memory) throws IllegalArgumentException {
        String serverBase = screen.getTextInput(GUID_SERVER_BASE);
        validateServerBaseURL(serverBase);
        memory.set(GUID_SERVER_BASE, serverBase);
        memory.set(GUID_USER_NAME, screen.getTextInput(GUID_USER_NAME));
        memory.set(GUID_PASSWORD, screen.getTextInput(GUID_PASSWORD));
        memory.set(GUID_PLAYER_NAME, screen.getTextInput(GUID_PLAYER_NAME));

        return STATE_IMMEDIATE_CLOSE;
    }

    @Override
    public final T login(Memory memory) throws IOException {
        if (memory.get(GUID_USER_NAME, String.class).isEmpty()) return loginOAuth(memory.get(GUID_SERVER_BASE, String.class));
        String baseUrl = transformServerBaseURL(memory.get(GUID_SERVER_BASE, String.class));
        String loginUrl = baseUrl + "/authserver/authenticate";
        String profileUrl = baseUrl + "/sessionserver/session/minecraft/profile/";

        // 主动提供 clientToken：服务端会把它存进该 client 并在 /authserver/refresh 强制比对。
        // 若省略，drasl 会为每次登录随机生成一个（auth.go:179-192），客户端一旦丢弃，
        // 之后的刷新必然 403 Invalid token —— 账号就变成「只有第一次能用」。
        String clientToken = generateClientToken();

        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);

        JsonObject root = new JsonObject();
        root.add("agent", agent);
        root.addProperty("username", memory.get(GUID_USER_NAME, String.class));
        root.addProperty("password", memory.get(GUID_PASSWORD, String.class));
        root.addProperty("clientToken", clientToken);

        // ignoreHttpStatus=true：Yggdrasil 的认证失败是标准 JSON 错误体（drasl 一律 403 + error/errorMessage），
        // 若先被网关的状态码校验拦下，就只剩 "HTTP 403"，用户看不到真正原因（也丢了 i18n 提示）。
        JsonObject json = http.postJson(loginUrl, root, true);
        throwIfError(json, "accountsx.account.fail.injector_login");

        String accessToken = requireAccessToken(json);
        clientToken = echoedClientToken(json, clientToken);

        String playerName = memory.get(GUID_PLAYER_NAME, String.class);
        List<Profile> profiles = readProfiles(json);
        if (profiles.size() == 1) {
            Profile profile = profiles.get(0);

            if (!playerName.isEmpty()) {
                if (!playerName.equals(profile.playerName)) {
                    throw new IOException("Player not found.");
                }
            }

            AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUUID);
            return createAccount(
                    accessToken, profile.playerName,
                    AccountUUID.parse(profile.playerUUID),
                    baseUrl,
                    profile.playerUUID,
                    getAccountName(baseUrl),
                    avatar == null ? null : avatar.key(),
                    avatar == null ? 0L : avatar.cachedAt(),
                    clientToken
            );
        } else {
            for (Profile profile : profiles) {
                if (playerName.equals(profile.playerName)) {
                    AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUUID);
                    return createAccount(
                            accessToken,
                            profile.playerName,
                            AccountUUID.parse(profile.playerUUID),
                            baseUrl,
                            profile.playerUUID,
                            getAccountName(baseUrl),
                            avatar == null ? null : avatar.key(),
                            avatar == null ? 0L : avatar.cachedAt(),
                            clientToken
                    );
                }
            }

            throw new PlayerNoLongerExistedException("Cannot find player which match " + playerName);
        }
    }

    @Override
    public final void refresh(T account) throws IOException {
        if (account.getLoginToken().startsWith("OAuth ")) {
            refreshOAuth(account);
            return;
        }
        String baseUrl = account.getServer();
        String refreshUrl = baseUrl + "/authserver/refresh";
        String profileUrl = baseUrl + "/sessionserver/session/minecraft/profile/";

        String clientToken = account.getClientToken();
        if (clientToken == null || clientToken.isEmpty()) {
            // 旧版本（2.0.0-beta.3 及更早）登录时未保存 clientToken，且当时也没有保存密码，
            // 无法凭现有载荷补出能通过服务端比对的值；只能让用户重新添加该账号。
            //
            // 已用真机（drasl 4.0.1）确认没有更省事的退路：refresh 省略 clientToken 与发送空字符串
            // 都稳定返回 403 ForbiddenOperationException: Invalid token，只有携带正确值才 200。
            // 因此这里直接给出可操作提示，不再白发一次注定失败的请求。
            throw new InjectorAuthException(
                    "accountsx.account.fail.injector_invalid_token",
                    "This injector account has no clientToken (created by an older version); add it again."
            );
        }

        JsonObject root = new JsonObject();
        root.addProperty("accessToken", account.getLoginToken());
        root.addProperty("clientToken", clientToken);

        // 同上：必须读得到 403 的错误体，才能把「凭据已失效」这类可操作原因告诉用户。
        JsonObject json = http.postJson(refreshUrl, root, true);
        throwIfError(json, "accountsx.account.fail.injector_invalid_token");

        String accessToken = requireAccessToken(json);
        String echoed = echoedClientToken(json, clientToken);

        // 关键顺序：**立刻**提交服务端已经轮换好的令牌，再去解析角色。
        // 刷新一旦被服务端接受，旧 accessToken 就已经作废（drasl 递增版本号）；若此时因为角色解析
        // 失败而把新令牌丢掉，账号会永久卡在「旧令牌已死、新令牌没保存」，只能重新添加。
        // 角色解析失败因此降级为「保留旧档案 + 记一条警告」，不再影响令牌落地。
        account.setLoginProfile(accessToken, account.getPreferredPlayerUUID());
        account.setProfile(accessToken, account.getAccountStorage().getPlayerName(), account.getAccountStorage().getPlayerUUID());
        if (!echoed.equals(clientToken)) {
            account.setClientToken(echoed);
        }

        Profile profile = resolveRefreshedProfile(account, json);
        if (profile == null) {
            return;
        }

        account.setLoginProfile(accessToken, profile.playerUUID());
        account.setProfile(accessToken, profile.playerName(), AccountUUID.parse(profile.playerUUID()));

        // 头像属于可选附加信息，失败由 AvatarService 自行吞掉。
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUUID());
        account.setAvatar(avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }

    /**
     * 解析刷新响应里的角色；解析不出来时保留账号原有档案并记警告（返回 {@code null}）。
     * 刷新本身已经成功，绝不能因为角色信息异常把整次刷新判为失败。
     */
    private Profile resolveRefreshedProfile(AbstractInjectorAccount account, JsonObject json) {
        try {
            List<Profile> profiles = readProfiles(json);
            if (profiles.size() == 1) {
                return profiles.get(0);
            }

            String preferredPlayerUUID = account.getPreferredPlayerUUID();
            for (Profile profile : profiles) {
                if (profile.playerUUID().equals(preferredPlayerUUID)) {
                    return profile;
                }
            }

            // 服务端不再返回首选角色（例如角色已被删除、或服务端只回 selectedProfile）。
            AccountsX.LOGGER.warn("Refreshed profile list does not contain the preferred player {}; keeping the previous profile.", preferredPlayerUUID);
            return null;
        } catch (RuntimeException e) {
            // PlayerNoLongerExistedException 也是 RuntimeException；解析失败不再影响令牌落地。
            AccountsX.LOGGER.warn("Cannot resolve the refreshed profile; keeping the previous one.", e);
            return null;
        }
    }

    private String getAccountName(String baseUrl) {
        try {
            JsonObject ygg = http.get(baseUrl);
            return ygg.get("meta").getAsJsonObject().get("serverName").getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record Profile(String playerName, String playerUUID) {}

    private static List<Profile> readProfiles(JsonObject json) {
        JsonElement selectedProfile = json.get("selectedProfile");
        if (selectedProfile != null) {
            JsonObject jo = selectedProfile.getAsJsonObject();
            String playerName = jo.get("name").getAsString();
            String playerUUID = jo.get("id").getAsString();

            return List.of(new Profile(playerName, playerUUID));
        } else {
            JsonArray availableProfiles = json.get("availableProfiles").getAsJsonArray();
            List<Profile> results = new ArrayList<>(availableProfiles.size());

            for (JsonElement availableProfile : availableProfiles) {
                JsonObject jo = availableProfile.getAsJsonObject();
                String playerName = jo.get("name").getAsString();
                String playerUUID = jo.get("id").getAsString();
                results.add(new Profile(playerName, playerUUID));
            }

            return results;
        }
    }

    private T loginOAuth(String server) throws IOException {
        String yggUrl = transformServerBaseURL(server);
        String profileUrl = yggUrl + "/sessionserver/session/minecraft/profile/";

        String openidConfigurationUrl;
        JsonObject ygg = http.get(yggUrl);
        if (ygg.get("meta") instanceof JsonObject meta &&
                meta.get("feature.openid_configuration_url") instanceof JsonPrimitive jp1 &&
                jp1.isString()) openidConfigurationUrl = jp1.getAsString();
        else throw new IOException("Invalid openid configuration url!");

        JsonObject config = http.get(openidConfigurationUrl);
        String deviceAuthorizationEndpoint = config.get("device_authorization_endpoint").getAsString();
        String tokenEndpoint = config.get("token_endpoint").getAsString();
        String clientId;

        String host = URI.create(yggUrl).getHost();
        if (OAuthConstants.OAUTH_CLIENT_IDS.containsKey(host)) clientId = OAuthConstants.OAUTH_CLIENT_IDS.get(host);
        else if (config.get("shared_client_id") instanceof JsonPrimitive jp2 &&
                jp2.isString()) clientId = jp2.getAsString();
        else throw new IOException("Invalid client id!");

        Platforms.getMinecraftPlatform().showToast("accountsx.account.oauth2.code.generating", null);

        Map<String, String> form1 = Map.of(
                "client_id", clientId,
                "scope", "openid offline_access Yggdrasil.PlayerProfiles.Select Yggdrasil.Server.Join"
        );
        JsonObject device = http.postForm(deviceAuthorizationEndpoint, form1);
        String deviceCode = device.get("device_code").getAsString();
        String userCode = device.get("user_code").getAsString();
        int interval;
        if (device.get("interval") instanceof JsonPrimitive jp &&
                jp.isNumber()) interval = jp.getAsInt();
        else interval = 5;
        int expires;
        if (device.get("expires_in") instanceof JsonPrimitive jp &&
                jp.isNumber()) expires = jp.getAsInt();
        else expires = 300;
        if (device.get("verification_uri_complete") instanceof JsonPrimitive jp && jp.isString()) {
            Platforms.getMinecraftPlatform().openBrowser(jp.getAsString());
            Platforms.getMinecraftPlatform().copyText(jp.getAsString());
        } else {
            Platforms.getMinecraftPlatform().copyText(userCode);
            // 1.10 修复：原为取出来即丢弃的死语句，导致无 verification_uri_complete 时不打开浏览器
            Platforms.getMinecraftPlatform().openBrowser(device.get("verification_uri").getAsString());
        }
        Platforms.getMinecraftPlatform().showToast("accountsx.account.oauth2.code.title", "accountsx.account.oauth2.code.desc", userCode);

        String accessToken = null, refreshToken = null, idToken = null;
        for (int i = 0; i < expires; i += interval) {
            try {
                Thread.sleep(Math.max(interval, 1) * 1000L);
            } catch (InterruptedException e) {
                throw new IOException("Interrupted.", e);
            }

            Map<String, String> form2 = Map.of(
                    "client_id", clientId,
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code", deviceCode
            );
            JsonObject token = http.postForm(tokenEndpoint, form2, true);

            JsonElement err = token.get("error");
            if (err == null) {
                accessToken = token.get("access_token").getAsString();
                refreshToken = token.get("refresh_token").getAsString();
                idToken = token.get("id_token").getAsString();
                break;
            }

            String error = err.getAsString();
            if (error.equals("authorization_pending")) continue;
            if (error.equals("expired_token")) throw new IOException("No character detected.");
            throw new IOException("Unknown error: " + error);
        }

        if (accessToken == null || refreshToken == null || idToken == null) throw new IOException("Invalid token.");

        String[] parts = idToken.split("\\.");
        if (parts.length < 2) throw new IOException("Invalid id token.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String payload = new String(decoder.decode(parts[1]));
        JsonObject userinfo = JsonParser.parseString(payload).getAsJsonObject();
        Profile profile = readProfiles(userinfo).get(0);

        JsonObject OAuth = new JsonObject();
        OAuth.addProperty("token_endpoint", tokenEndpoint);
        OAuth.addProperty("refresh_token", refreshToken);
        OAuth.addProperty("client_id", clientId);

        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUUID);
        T account = createAccount(
                accessToken,
                profile.playerName,
                AccountUUID.parse(profile.playerUUID),
                yggUrl,
                profile.playerUUID,
                getAccountName(yggUrl),
                avatar == null ? null : avatar.key(),
                avatar == null ? 0L : avatar.cachedAt(),
                // OAuth 账号走 OAuth 刷新端点，不使用 Yggdrasil clientToken。
                null
        );
        account.setLoginProfile("OAuth " + OAuth, profile.playerUUID);
        return account;
    }

    private void refreshOAuth(T account) throws IOException {
        String OAuthStr = account.getLoginToken().substring(6);
        JsonObject OAuth = JsonParser.parseString(OAuthStr).getAsJsonObject();

        String tokenEndpoint = OAuth.get("token_endpoint").getAsString();
        String refreshToken = OAuth.get("refresh_token").getAsString();
        String clientId = OAuth.get("client_id").getAsString();

        Map<String, String> form = Map.of(
                "client_id", clientId,
                "grant_type", "refresh_token",
                "refresh_token", refreshToken
        );
        JsonObject token = http.postForm(tokenEndpoint, form);

        JsonElement err = token.get("error");
        if (err !=null) throw new IOException("Unknown error: " + err.getAsString());
        String accessToken = token.get("access_token").getAsString();
        refreshToken = token.get("refresh_token").getAsString();
        OAuth.addProperty("refresh_token", refreshToken);
        String idToken = token.get("id_token").getAsString();

        String[] parts = idToken.split("\\.");
        if (parts.length < 2) throw new IOException("Invalid id token.");
        Base64.Decoder decoder = Base64.getUrlDecoder();
        String payload = new String(decoder.decode(parts[1]));
        JsonObject userinfo = JsonParser.parseString(payload).getAsJsonObject();
        Profile profile = readProfiles(userinfo).get(0);
        String profileUrl = account.getServer() + "/sessionserver/session/minecraft/profile/";

        account.setProfile(accessToken, profile.playerName, AccountUUID.parse(profile.playerUUID));
        account.setLoginProfile("OAuth " + OAuth, profile.playerUUID);
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUUID);
        account.setAvatar(avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }
}
