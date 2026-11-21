package top.syshub.accountsx.common.auth.yggdrasil;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorAuthException;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.net.JdkHttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.server.AuthServerKind;
import top.syshub.accountsx.common.auth.server.AuthServerRegistry;
import top.syshub.accountsx.common.auth.oauth.AccountOAuth;
import top.syshub.accountsx.common.auth.oauth.OAuthEndpoints;

/**
 * 两步式 device-code（P5 外置登录界面所用 API）：取码与轮询分离，取消可打断。
 * fixture 一律 fake- 前缀，无任何真实凭据；id_token 用本地生成的 RSA 密钥对真实签名，
 * 公钥经 stub 的 jwks 端点发布。
 */
class DeviceCodeFlowTest {
    private static final String UUID32 = "069a79f444e94726a5befca90e38aaf5";

    private HttpServer server;
    private String base;
    private String apiRoot;
    private String jwksUri;
    /** 签名用密钥对；被测试替换成「别人的私钥」时，jwks 仍发布原公钥。 */
    private KeyPair keyPair;
    private KeyPair jwksKeyPair;
    private AuthServer authServer;
    private final AtomicInteger deviceHits = new AtomicInteger();
    private final AtomicInteger tokenHits = new AtomicInteger();
    /** true 时 token 端点一直回 authorization_pending，用于验证取消。 */
    private volatile boolean pendingForever;
    /** 非 null 时 token 端点改回这个错误体（HTTP 400），用于验证错误原因不被状态码吞掉。 */
    private volatile String tokenErrorBody;
    /** true 时成功响应省略 refresh_token，用于验证「缺字段报字段名」而非 NPE。 */
    private volatile boolean omitRefreshToken;

    @BeforeEach
    void startStub() throws Exception {
        keyPair = KeyPairGenerator.getInstance("RSA").genKeyPair();
        jwksKeyPair = keyPair;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        apiRoot = base + "/api/yggdrasil";
        jwksUri = apiRoot + "/oauth/jwks";

        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            JsonObject body = null;
            int code = 200;
            switch (key) {
                case "POST /api/yggdrasil/oauth/device" -> {
                    deviceHits.incrementAndGet();
                    body = new JsonObject();
                    body.addProperty("device_code", "fake-device");
                    body.addProperty("user_code", "FAKE-CODE");
                    body.addProperty("verification_uri", base + "/oauth/verify");
                    body.addProperty("verification_uri_complete", base + "/oauth/verify?code=FAKE-CODE");
                    body.addProperty("interval", 0);
                    body.addProperty("expires_in", 0);
                }
                case "POST /api/yggdrasil/oauth/token" -> {
                    tokenHits.incrementAndGet();
                    String errorBody = tokenErrorBody;
                    if (errorBody != null) {
                        code = 400;
                        body = JsonParser.parseString(errorBody).getAsJsonObject();
                    } else {
                        body = new JsonObject();
                        if (pendingForever) {
                            body.addProperty("error", "authorization_pending");
                        } else {
                            body.addProperty("access_token", "fake-access2");
                            if (!omitRefreshToken)
                                body.addProperty("refresh_token", "fake-refresh2");
                            body.addProperty("id_token", idToken());
                            body.addProperty("token_type", "Bearer");
                            body.addProperty("expires_in", 3600);
                        }
                    }
                }
                case "GET /api/yggdrasil/oauth/jwks" -> body = jwks();
                case "GET /api/yggdrasil/sessionserver/session/minecraft/profile/" + UUID32 -> {
                    body = new JsonObject();
                    body.add("properties", new JsonArray());
                }
                default -> code = 404;
            }
            byte[] bytes = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
            if (body != null) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        AuthServerRegistry registry = new AuthServerRegistry(JdkHttpGateway.INSTANCE);
        AbstractInjectorAccountProvider.installRegistry(registry);
        authServer = new AuthServer(apiRoot, AuthServerKind.YGGDRASIL, "FakeInjector",
                new OAuthEndpoints("fake-shared-client", apiRoot + "/oauth/token", apiRoot + "/oauth/device", null,
                        apiRoot, jwksUri),
                0L);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    /** 用本地私钥签一张 kid=fake-kid 的 id_token，claims 与真实 YGGC 响应同形。 */
    private String idToken() throws IOException {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        long now = System.currentTimeMillis() / 1000L;
        String header = enc.encodeToString(("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"fake-kid\"}")
                .getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"" + apiRoot + "\",\"aud\":\"fake-shared-client\","
                + "\"sub\":\"fake-user\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + ",\"availableProfiles\":"
                + "[{\"name\":\"Alex\",\"id\":\"" + UUID32 + "\"}]}")
                .getBytes(StandardCharsets.UTF_8));
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
        RSAPublicKey publicKey = (RSAPublicKey) jwksKeyPair.getPublic();
        JsonObject jwk = new JsonObject();
        jwk.addProperty("kty", "RSA");
        jwk.addProperty("kid", "fake-kid");
        jwk.addProperty("n", unsigned(publicKey.getModulus()));
        jwk.addProperty("e", unsigned(publicKey.getPublicExponent()));
        JsonObject document = new JsonObject();
        JsonArray keys = new JsonArray();
        keys.add(jwk);
        document.add("keys", keys);
        return document;
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0)
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void supportsDeviceCode_requiresAllThreeEndpoints() {
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(authServer)).isTrue();
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(new AuthServer(apiRoot, AuthServerKind.YGGDRASIL,
                "FakeInjector", new OAuthEndpoints("fake-client", apiRoot + "/oauth/token", null, null), 0L)))
                .isFalse();
        assertThat(YggdrasilDeviceCodeFlow.supportsDeviceCode(new AuthServer(apiRoot, AuthServerKind.YGGDRASIL,
                "FakeInjector", null, 0L))).isFalse();
    }

    @Test
    void requestDeviceCode_clampsTimingsAndPrefersCompleteUri() throws Exception {
        YggdrasilDeviceCodeFlow.DeviceCode code = YggdrasilDeviceCodeFlow.requestDeviceCode(JdkHttpGateway.INSTANCE, authServer);

        assertThat(code.deviceCode()).isEqualTo("fake-device");
        assertThat(code.userCode()).isEqualTo("FAKE-CODE");
        assertThat(code.clientId()).isEqualTo("fake-shared-client");
        assertThat(code.tokenEndpoint()).isEqualTo(apiRoot + "/oauth/token");
        // 服务端给 0 也不能忙等：至少 1 秒
        assertThat(code.interval()).isEqualTo(1);
        assertThat(code.expiresIn()).isEqualTo(1);
        assertThat(code.browserUrl()).isEqualTo(base + "/oauth/verify?code=FAKE-CODE");
        assertThat(deviceHits).hasValue(1);
    }

    @Test
    void awaitToken_returnsResultAndCancelledSupplierAborts() throws Exception {
        YggdrasilDeviceCodeFlow.DeviceCode code = YggdrasilDeviceCodeFlow.requestDeviceCode(JdkHttpGateway.INSTANCE, authServer);

        YggdrasilAuthResult result = YggdrasilDeviceCodeFlow.awaitToken(JdkHttpGateway.INSTANCE, authServer, code,
                millis -> { }, () -> false);
        assertThat(result.accessToken()).isEqualTo("fake-access2");
        assertThat(result.refreshToken()).isEqualTo("fake-refresh2");
        assertThat(result.playerName()).isEqualTo("Alex");

        pendingForever = true;
        assertThatThrownBy(() -> YggdrasilDeviceCodeFlow.awaitToken(JdkHttpGateway.INSTANCE, authServer, code,
                millis -> { }, () -> true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Cancelled");
    }

    @Test
    void loginDeviceCode_createsAccountWithoutProbing() throws Exception {
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        YggdrasilDeviceCodeFlow.DeviceCode code = provider.fetchDeviceCode(authServer);

        AbstractInjectorAccount[] box = new AbstractInjectorAccount[1];
        // setLoginProfile 要求 worker 线程：与生产一致地在调度器上跑
        TaskScheduler.submit(() -> box[0] = provider.loginDeviceCode(authServer, code, () -> false)).get();

        AbstractInjectorAccount account = box[0];
        assertThat(account.getServer()).isEqualTo(apiRoot);
        assertThat(account.getAccountStorage().getPlayerName()).isEqualTo("Alex");
        assertThat(account.getLoginToken()).startsWith(AccountOAuth.PREFIX).contains("fake-refresh2");
        assertThat(deviceHits).hasValue(1);
        assertThat(tokenHits).hasValue(1);
    }

    @Test
    void loginDeviceCode_rejectsIdTokenSignedByAnotherKey() throws Exception {
        // 同一台服务器、同一端点，但令牌是别人用自己的私钥签的：一律拒绝。
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(JdkHttpGateway.INSTANCE);
        KeyPair other = KeyPairGenerator.getInstance("RSA").genKeyPair();
        KeyPair original = keyPair;
        keyPair = other;
        try {
            YggdrasilDeviceCodeFlow.DeviceCode code = provider.fetchDeviceCode(authServer);
            assertThatThrownBy(() -> TaskScheduler.submit(
                    () -> provider.loginDeviceCode(authServer, code, () -> false)).get())
                    .hasRootCauseMessage("id token signature does not match any published key (kid=fake-kid)");
        } finally {
            keyPair = original;
        }
    }

    @Test
    void awaitToken_missingRefreshTokenReportsFieldName() throws Exception {
        omitRefreshToken = true;
        YggdrasilDeviceCodeFlow.DeviceCode code =
                YggdrasilDeviceCodeFlow.requestDeviceCode(JdkHttpGateway.INSTANCE, authServer);

        assertThatThrownBy(() -> YggdrasilDeviceCodeFlow.awaitToken(JdkHttpGateway.INSTANCE, authServer, code,
                millis -> { }, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("refresh_token")
                .hasMessageContaining("fields=");
    }

    @Test
    void refresh_reportsOauthErrorBodyInsteadOfBareStatus() {
        tokenErrorBody = "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token was revoked\"}";
        String envelope = AccountOAuth.envelope("fake-refresh1", apiRoot + "/oauth/token", "fake-shared-client")
                .substring(AccountOAuth.PREFIX.length());

        assertThatThrownBy(() -> YggdrasilDeviceCodeFlow.refresh(JdkHttpGateway.INSTANCE, envelope, authServer))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("invalid_grant")
                .hasMessageContaining("refresh token was revoked");
    }

    @Test
    void refresh_mapsRevokedCredentialsToAnActionableMessage() {
        // 服务端说「这份凭据不认了」，界面要给出「删除后重新添加」，而不是「未知错误」。
        tokenErrorBody = "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token was revoked\"}";
        String envelope = AccountOAuth.envelope("fake-refresh1", apiRoot + "/oauth/token", "fake-shared-client")
                .substring(AccountOAuth.PREFIX.length());

        assertThatThrownBy(() -> YggdrasilDeviceCodeFlow.refresh(JdkHttpGateway.INSTANCE, envelope, authServer))
                .isInstanceOf(InjectorAuthException.class)
                .extracting(e -> ((InjectorAuthException) e).getTranslationKey())
                .isEqualTo("accountsx.account.fail.injector_invalid_token");
    }

    @Test
    void refresh_leavesOtherOauthErrorsUnactionable() {
        // access_denied 是用户自己拒绝授权，删掉账号重加解决不了问题：不该给出「凭据已失效」。
        tokenErrorBody = "{\"error\":\"access_denied\",\"error_description\":\"user said no\"}";
        String envelope = AccountOAuth.envelope("fake-refresh1", apiRoot + "/oauth/token", "fake-shared-client")
                .substring(AccountOAuth.PREFIX.length());

        assertThatThrownBy(() -> YggdrasilDeviceCodeFlow.refresh(JdkHttpGateway.INSTANCE, envelope, authServer))
                .isExactlyInstanceOf(IOException.class)
                .hasMessageContaining("access_denied");
    }

    @Test
    void refresh_keepsExistingRefreshTokenWhenServerDoesNotRotate() throws Exception {
        // 规范里 refresh_token 只在申请了 offline_access 时才必须返回；服务端不轮换时沿用旧值，
        // 否则一次「成功」的刷新会把账号变成再也刷不动的死账号。
        omitRefreshToken = true;
        String envelope = AccountOAuth.envelope("fake-refresh1", apiRoot + "/oauth/token", "fake-shared-client")
                .substring(AccountOAuth.PREFIX.length());

        YggdrasilAuthResult result = YggdrasilDeviceCodeFlow.refresh(JdkHttpGateway.INSTANCE, envelope, authServer);

        assertThat(result.accessToken()).isEqualTo("fake-access2");
        assertThat(result.refreshToken()).isEqualTo("fake-refresh1");
        assertThat(result.playerName()).isEqualTo("Alex");
    }
}
