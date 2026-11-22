package top.syshub.accountsx.common.auth.yggdrasil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.auth.server.AuthServerRegistry;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.net.JdkHttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.server.AuthServerKind;
import top.syshub.accountsx.common.auth.oauth.AccountOAuth;
import top.syshub.accountsx.common.auth.YggdrasilFixtures;

/**
 * 端到端（最简 Yggdrasil stub）：密码登录 / device-code 登录 / OAuth 刷新
 * 全部打真 HTTP stub，断言请求次数——"同服务器多账号只探测一次"。
 * fixture 一律 fake- 前缀，无任何真实凭据。
 */
class InjectorLoginStubTest {
    private AuthServerRegistry registry;
    private static final String UUID32 = "069a79f444e94726a5befca90e38aaf5";

    private HttpServer server;
    private String base;
    private String apiRoot;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** id_token 用本地私钥真实签名，公钥经 stub 的 jwks 端点发布（验签是硬性要求，不能拿明文假令牌糊过去）。 */
    private KeyPair keyPair;

    private static void bump(Map<String, AtomicInteger> hits, String key) {
        hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private int count(String key) {
        return hits.getOrDefault(key, new AtomicInteger()).get();
    }

    @BeforeEach
    void startStub() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").genKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        apiRoot = base + "/api/yggdrasil";

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String key = method + " " + path;
            bump(hits, key);
            JsonObject body = null;
            int code = 200;
            switch (key) {
                case "GET /" -> {
                    exchange.getResponseHeaders().add("X-Authlib-Injector-API-Location", "/api/yggdrasil");
                }
                case "GET /api/yggdrasil" -> {
                    try {
                        body = YggdrasilFixtures.rootMeta(apiRoot);
                    } catch (IOException e) {
                        code = 500;
                    }
                }
                case "GET /api/yggdrasil/.well-known/openid" -> body = YggdrasilFixtures.openidConfig(apiRoot);
                case "GET /api/yggdrasil/oauth/jwks" -> body = jwks();
                case "POST /api/yggdrasil/authserver/authenticate" -> body = YggdrasilFixtures.profile("Steve", UUID32);
                case "POST /api/yggdrasil/oauth/device" -> {
                    body = new JsonObject();
                    body.addProperty("device_code", "fake-device");
                    body.addProperty("user_code", "FAKE-CODE");
                    body.addProperty("verification_uri", base + "/oauth/verify");
                    body.addProperty("interval", 1);
                    body.addProperty("expires_in", 1);
                }
                case "POST /api/yggdrasil/oauth/token" -> {
                    body = new JsonObject();
                    body.addProperty("access_token", "fake-access2");
                    body.addProperty("refresh_token", "fake-refresh2");
                    body.addProperty("id_token", fakeIdToken());
                    body.addProperty("token_type", "Bearer");
                    body.addProperty("expires_in", 3600);
                }
                case "GET /api/yggdrasil/sessionserver/session/minecraft/profile/" + UUID32 -> {
                    body = new JsonObject();
                    body.add("properties", new JsonArray());
                }
                default -> {
                    code = 404;
                }
            }
            byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
            if (body != null)
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        registry = new AuthServerRegistry(JdkHttpGateway.INSTANCE);
        AbstractInjectorAccountProvider.installRegistry(registry);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
        /* registry cleaned up by test teardown */
    }

    /** 签一张 kid=fake-kid 的 id_token，claims 与真实 YGGC 响应同形（iss/aud/iat/exp 全齐）。 */
    private String fakeIdToken() throws IOException {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        long now = System.currentTimeMillis() / 1000L;
        String header = enc.encodeToString(("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"fake-kid\"}")
                .getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"" + apiRoot + "\",\"aud\":\"fake-shared-client\","
                + "\"sub\":\"fake-user\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + ",\"availableProfiles\":"
                + "[{\"name\":\"Alex\",\"id\":\"" + UUID32 + "\"}]}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + sign(header + "." + payload);
    }

    private String sign(String signingInput) throws IOException {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private JsonObject jwks() {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kty", "RSA");
        jwk.addProperty("kid", "fake-kid");
        jwk.addProperty("n", unsigned(publicKey.getModulus()));
        jwk.addProperty("e", unsigned(publicKey.getPublicExponent()));
        JsonArray keys = new JsonArray();
        keys.add(jwk);
        JsonObject document = new JsonObject();
        document.add("keys", keys);
        return document;
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0)
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void passwordLogin_reusesServerEntryAcrossTwoLogins() throws Exception {
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        // 服务器条目由「添加认证服务器」建立：ALI 解析 + 一次探测
        AuthServer server = registry.add(AuthServerKind.YGGDRASIL, base);

        AbstractInjectorAccount[] box = new AbstractInjectorAccount[1];
        TaskScheduler.submit(() -> box[0] = provider.loginPassword(server, "fake-user", "fake-pass")).get();
        AbstractInjectorAccount first = box[0];
        assertThat(first.getServer()).isEqualTo(apiRoot);
        assertThat(first.getAccountStorage().getPlayerName()).isEqualTo("Steve");
        assertThat(first.getLoginToken()).isEqualTo("fake-access");
        assertThat(count("GET /")).isEqualTo(1);
        assertThat(count("GET /api/yggdrasil")).isEqualTo(1);
        assertThat(count("POST /api/yggdrasil/authserver/authenticate")).isEqualTo(1);

        TaskScheduler.submit(() -> box[0] = provider.loginPassword(server, "fake-user", "fake-pass")).get();
        AbstractInjectorAccount second = box[0];
        assertThat(second.getServer()).isEqualTo(apiRoot);
        // 登录路径直接拿条目里的端点：第二个账号零探测，只多一次 authenticate
        assertThat(count("GET /api/yggdrasil")).isEqualTo(1);
        assertThat(count("GET /")).isEqualTo(1);
        assertThat(count("POST /api/yggdrasil/authserver/authenticate")).isEqualTo(2);

        assertThat(server.displayName()).isEqualTo("FakeInjector");
        assertThat(server.apiRoot()).isEqualTo(apiRoot);
        assertThat(server.oauth().clientId()).isEqualTo("fake-shared-client");

        JsonArray out = new JsonArray();
        registry.persist(out);
        assertThat(out.asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(j -> apiRoot.equals(j.get("apiRoot").getAsString()))).isTrue();
    }

    @Test
    void deviceCodeLogin_usesCachedEndpoints() throws Exception {
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        AuthServer server = registry.add(AuthServerKind.YGGDRASIL, base);
        AbstractInjectorAccount[] box = new AbstractInjectorAccount[1];
        // setLoginProfile 要求 worker 线程：登录编排在调度器上跑（与生产一致）
        TaskScheduler.submit(() -> {
            YggdrasilDeviceCodeFlow.DeviceCode code = provider.fetchDeviceCode(server);
            box[0] = provider.loginDeviceCode(server, code, () -> false);
        }).get();
        AbstractInjectorAccount account = box[0];

        assertThat(account.getLoginToken()).startsWith(AccountOAuth.PREFIX);
        assertThat(account.getLoginToken()).contains("fake-refresh2");
        assertThat(account.getAccountStorage().getPlayerName()).isEqualTo("Alex");
        // token 响应的 expires_in=3600 要变成启动复用判据（60 秒安全余量内为正）
        assertThat(account.getAccountStorage().getExpiresAt())
                .isPositive()
                .isLessThan(System.currentTimeMillis() + 3_600_000L);
        assertThat(count("POST /api/yggdrasil/oauth/device")).isEqualTo(1);
        assertThat(count("POST /api/yggdrasil/oauth/token")).isEqualTo(1);
        // 端点来自注册表条目：openid 配置只在加服探测时取过一次
        assertThat(count("GET /api/yggdrasil/.well-known/openid")).isEqualTo(1);
    }

    @Test
    void oauthRefresh_rotatesEnvelope() throws Exception {
        // 刷新要验 id_token 的发行方与签名：这两项来自注册表条目（加服时探测得到）
        AuthServer server = registry.add(AuthServerKind.YGGDRASIL, base);
        String envelope = AccountOAuth.envelope("fake-refresh", server.oauth().tokenEndpoint(), "fake-shared-client");
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = new AuthlibInjectorAccountProvider.AuthlibInjectorAccount(
                "fake-access", "Alex", java.util.UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
                apiRoot, UUID32, "FakeInjector", null, 0L);
        account.setLoginTokenForMigration(envelope, UUID32);

        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        TaskScheduler.submit(() -> provider.refresh(account)).get();

        assertThat(account.getLoginToken()).startsWith(AccountOAuth.PREFIX);
        assertThat(account.getLoginToken()).contains("fake-refresh2");
        assertThat(count("POST /api/yggdrasil/oauth/token")).isEqualTo(1);
        // 刷新轮换后的新令牌同样要带上 expires_at，否则下次启动仍会全量轮换
        assertThat(account.getAccountStorage().getExpiresAt())
                .isPositive()
                .isLessThan(System.currentTimeMillis() + 3_600_000L);
    }
}
