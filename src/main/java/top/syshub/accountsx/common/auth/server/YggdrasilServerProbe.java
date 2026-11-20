package top.syshub.accountsx.common.auth.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.NetworkUtils;
import top.syshub.accountsx.common.auth.oauth.OAuthEndpoints;

public final class YggdrasilServerProbe {
    private YggdrasilServerProbe() {
    }

    public record Probed(String displayName, AuthServerMetadata metadata, OAuthEndpoints oauth,
            boolean deviceScopesRejected) {
        public Probed(String displayName, AuthServerMetadata metadata, OAuthEndpoints oauth) {
            this(displayName, metadata, oauth, false);
        }
    }

    private record OAuthDiscovery(OAuthEndpoints endpoints, boolean deviceScopesRejected) {
        private static final OAuthDiscovery ABSENT = new OAuthDiscovery(null, false);
    }

    public static Probed probe(HttpGateway http, String apiRoot, Map<String, String> knownClientIds)
            throws IOException {
        JsonObject root = http.get(apiRoot);
        JsonObject meta = root.get("meta") instanceof JsonObject m ? m : null;
        String displayName = meta != null ? str(meta.get("serverName")) : null;
        if (displayName == null)
            displayName = hostOf(apiRoot);
        AuthServerMetadata metadata = readMetadata(root);
        OAuthDiscovery discovery = discoverOAuth(http, meta, apiRoot, knownClientIds);
        return new Probed(displayName, metadata, discovery.endpoints(), discovery.deviceScopesRejected());
    }

    /** apiRoot 的 host；解析不出来时退回 apiRoot 本身（仅用于展示名兜底）。 */
    private static String hostOf(String apiRoot) {
        try {
            String host = URI.create(apiRoot).getHost();
            return host != null ? host : apiRoot;
        } catch (IllegalArgumentException e) {
            return apiRoot;
        }
    }

    /**
     * 把用户输入的地址解析成 API 根地址（启动器技术规范「API 地址指示」）。
     *
     * <p>顺序是：对输入地址发 GET（跟随重定向）→ 响应头有
     * {@code X-Authlib-Injector-API-Location} 就取它的值（可为相对地址）→
     * 没有该头时不猜路径，当前 URL（跟随重定向后的最终地址）本身就是 API 地址。</p>
     *
     * <p>早先实现在没有 ALI 头时硬拼 {@code scheme://authority/api/yggdrasil}，
     * 对 API 根不在该路径的服务器会解析到不存在的地址；同时用 HEAD 探测，
     * 部分服务器对 HEAD 直接 405。</p>
     */
    public static String resolveApiRoot(String locator, HttpGateway http) throws IOException {
        String requested = buildUrl(locator.trim());
        HttpGateway.Headers response;
        try {
            response = http.getHeaders(requested);
        } catch (IOException e) {
            throw new IOException("Failed to resolve API location: " + requested, e);
        }
        String current = response.finalUri() != null ? response.finalUri().toString() : requested;
        List<String> apiLocations = NetworkUtils.getHeaderIgnoreCase(response.headers(), "X-Authlib-Injector-API-Location");
        if (apiLocations == null || apiLocations.isEmpty())
            return current;
        String location = apiLocations.get(0);
        if (location == null || location.isBlank())
            return current;
        // ALI 可能是相对地址，也可能自指（等于当前 URL），两种都由 resolveLocation 收敛。
        return NetworkUtils.resolveLocation(current, location.trim());
    }

    static String buildUrl(String server) {
        try {
            URI u = URI.create(server);
            if (u.getScheme() != null)
                return server;
            return "https://" + server;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid server URL: " + server, e);
        }
    }

    static AuthServerMetadata readMetadata(JsonObject root) throws IOException {
        List<PublicKey> keys;
        JsonElement keyElement = root.get("signaturePublickey");
        if (keyElement instanceof JsonPrimitive jp && jp.isString()) {
            try {
                keys = List.of(parseSignaturePublicKey(jp.getAsString()));
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new IOException("Invalid Yggdrasil public key!", e);
            }
        } else {
            throw new IOException("Invalid Yggdrasil public key!");
        }
        List<String> skinDomains = new ArrayList<>();
        if (root.get("skinDomains") instanceof JsonArray ja) {
            for (JsonElement je : ja) {
                if (je instanceof JsonPrimitive domain && domain.isString())
                    skinDomains.add(domain.getAsString());
                else
                    throw new IOException("Invalid Yggdrasil skinDomains!");
            }
        } else {
            throw new IOException("Invalid Yggdrasil skinDomains!");
        }
        return new AuthServerMetadata(keys, keys, List.copyOf(skinDomains), profileKeySupported(root));
    }

    /**
     * 读取 {@code meta.feature.enable_profile_key}。
     *
     * <p>严格判断为布尔 {@code true}：authlib-injector 规范里这个开关存在多种历史写法，
     * 字符串 {@code "true"} 或缺失都不能当作已声明——误判会让客户端对支持签名公钥的服务端
     * 伪造密钥对，登录直接被踢（"无效的玩家档案公钥签名"）。</p>
     */
    private static boolean profileKeySupported(JsonObject root) {
        if (root.get("meta") instanceof JsonObject meta
                && meta.get("feature.enable_profile_key") instanceof JsonPrimitive flag
                && flag.isBoolean()) {
            return flag.getAsBoolean();
        }
        return false;
    }

    private static OAuthDiscovery discoverOAuth(HttpGateway http, JsonObject meta, String apiRoot,
            Map<String, String> knownClientIds) throws IOException {
        String openidUrl = meta == null ? null : str(meta.get("feature.openid_configuration_url"));
        if (openidUrl == null)
            return OAuthDiscovery.ABSENT;
        // clientId 只有两个来源：内置 apiRoot 表 > YGGC（Yggdrasil Connect）openid 配置里的 shared_client_id。
        // 表的键就是 apiRoot 本身，不是主机名：client_id 按 API 根注册，同一域名可以有不同 API 根，
        // 也不能用 ALI 解析过程中的中间域名（解析可能 301 跨域）。两处都取不到就判定该服务器不支持 OAuth。
        String clientId = knownClientIds == null ? null : knownClientIds.get(ApiRootKey.of(apiRoot));
        JsonObject config = http.get(openidUrl);
        // 设备码登录申请的 scope 固定带 openid 与 Yggdrasil.PlayerProfiles.Select；服务器若明确
        // 声明了 scopes_supported 却不含它们，令牌请求只会拿回 invalid_scope。宁可不出设备码入口
        // （密码登录照旧可用），也不要给一个点了必然失败的按钮。
        boolean deviceScopesRejected = !scopesAllowDeviceFlow(config);
        String device = deviceScopesRejected ? null : str(config.get("device_authorization_endpoint"));
        String token = str(config.get("token_endpoint"));
        String userinfo = str(config.get("userinfo_endpoint"));
        // issuer / jwks_uri 只服务于 id_token 校验：没有它们就无法确认令牌的签发者与签名密钥。
        String issuer = str(config.get("issuer"));
        String jwks = str(config.get("jwks_uri"));
        if (clientId == null)
            clientId = sharedClientId(config);
        OAuthEndpoints endpoints = clientId == null ? null : new OAuthEndpoints(clientId, token, device, userinfo, issuer, jwks);
        return new OAuthDiscovery(endpoints, deviceScopesRejected);
    }

    /**
     * 服务器的 {@code scopes_supported} 是否允许设备码登录。
     *
     * <p>缺失或空数组一律放行：无法判断时不替服务器做主，避免把一个能用的服务器判死；
     * 只有服务器明确列出了 scope 集合、且缺了设备码流程必需的项时才禁用。</p>
     */
    static boolean scopesAllowDeviceFlow(JsonObject config) {
        JsonElement scopes = config.get("scopes_supported");
        if (scopes == null || !scopes.isJsonArray())
            return true;
        JsonArray array = scopes.getAsJsonArray();
        if (array.isEmpty())
            return true;
        boolean openid = false, select = false;
        for (JsonElement e : array) {
            if (!(e instanceof JsonPrimitive p) || !p.isString())
                continue;
            String scope = p.getAsString();
            openid |= "openid".equals(scope);
            select |= "Yggdrasil.PlayerProfiles.Select".equals(scope);
        }
        return openid && select;
    }

    static PublicKey parseSignaturePublicKey(String pem)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        pem = pem.replace("\n", "").replace("\r", "");
        String header = "-----BEGIN PUBLIC KEY-----";
        String end = "-----END PUBLIC KEY-----";
        if (!pem.startsWith(header) || !pem.endsWith(end))
            throw new IOException("Bad key format");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(pem.substring(header.length(), pem.length() - end.length()))));
    }

    private static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /** 读 YGGC openid 配置里的 shared_client_id（规范把它放这里，根 meta 中不会有）：字符串与数字都要认。 */
    static String sharedClientId(JsonObject json) {
        JsonElement e = json == null ? null : json.get("shared_client_id");
        return e instanceof JsonPrimitive p && (p.isString() || p.isNumber()) ? p.getAsString() : null;
    }
}
