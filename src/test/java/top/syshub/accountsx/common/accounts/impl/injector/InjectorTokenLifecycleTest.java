package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.auth.oauth.AccountOAuth;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.server.AuthServerKind;
import top.syshub.accountsx.common.auth.server.AuthServerRegistry;
import top.syshub.accountsx.common.auth.oauth.OAuthEndpoints;
import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.net.JdkHttpGateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌生命周期：启动复用（B4）与删除撤销（B13）。
 *
 * <p>两件事必须在 provider 层验证，因为它们都是「发哪个请求、失败怎么办」的决定：复用走
 * {@code /authserver/validate}（不轮换令牌），撤销走 {@code /authserver/invalidate}（失败必须静默）。</p>
 */
class InjectorTokenLifecycleTest {

    private static final String API_ROOT = "https://ygg.invalid/api/yggdrasil";

    /** 记录 POST、可按需返回空体/错误体或抛 IO 的假网关。 */
    private static final class RecordingGateway implements HttpGateway {
        private final List<String> urls = new ArrayList<>();
        private final List<JsonElement> bodies = new ArrayList<>();
        private JsonObject response;
        private boolean offline;

        @Override
        public JsonObject get(String url) {
            throw new UnsupportedOperationException("fake: get not expected");
        }

        /** userinfo 校验（OAuth 信封账号）的答案：默认按「有响应体 → 200」答复。 */
        private JsonObject getResponse;

        /** userinfo 的状态码；{@code getResponse == null} 时用它表达「无效 / 服务端故障」。 */
        private int getUserinfoStatus = 401;

        @Override
        public JsonObject get(String url, Map<String, String> headers) throws IOException {
            this.urls.add(url);
            if (this.getResponse == null)
                throw new IOException("HTTP " + this.getUserinfoStatus);
            return this.getResponse;
        }

        @Override
        public Result getWithStatus(String url, Map<String, String> headers) {
            this.urls.add(url);
            if (this.getResponse != null)
                return new Result(200, this.getResponse);
            return new Result(this.getUserinfoStatus, null);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) throws IOException {
            return postJson(url, body, false);
        }

        /**
         * validate 走这条：真服务端用状态码表达结论——204 = 有效，403 + error 体 = 无效。
         * 假网关照这个形状答复，判定逻辑才不会被一个「什么都回 200」的桩掩盖。
         */
        @Override
        public Result postJsonWithStatus(String url, JsonElement body) throws IOException {
            this.urls.add(url);
            this.bodies.add(body);
            if (this.offline)
                throw new IOException("fake: network down");
            if (this.response == null)
                return new Result(204, null);
            return new Result(403, this.response);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) throws IOException {
            this.urls.add(url);
            this.bodies.add(body);
            if (this.offline) {
                throw new IOException("fake: network down");
            }
            return this.response;
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException("fake: postForm not expected");
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException("fake: postForm(ignore) not expected");
        }

        @Override
        public Headers getHeaders(String url) {
            throw new UnsupportedOperationException("fake: getHeaders not expected");
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }

        JsonObject body(int index) {
            return this.bodies.get(index).getAsJsonObject();
        }
    }

    private final RecordingGateway gateway = new RecordingGateway();

    private final AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(this.gateway);

    @BeforeEach
    void installRegistry() {
        AuthServerRegistry registry = new AuthServerRegistry(JdkHttpGateway.INSTANCE);
        JsonArray servers = new JsonArray();
        servers.add(new AuthServer(API_ROOT, AuthServerKind.YGGDRASIL, "FakeInjector", null, 0L).toJson());
        // load() 纯内存：不要用 add()，那会真的去探测 https://ygg.invalid。
        registry.load(servers);
        AbstractInjectorAccountProvider.installRegistry(registry);
    }

    private static AuthlibInjectorAccountProvider.AuthlibInjectorAccount account(String accessToken) {
        return new AuthlibInjectorAccountProvider.AuthlibInjectorAccount(accessToken, "Alex",
                UUID.fromString("11111111-1111-1111-1111-111111111111"), API_ROOT, null, "FakeInjector", null, 0L);
    }

    @Test
    void canReuseToken_validateSucceeds_returnsTrue() throws Exception {
        // 204：网关解析成 null，是「令牌仍有效」的唯一确定答复。
        this.gateway.response = null;
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = account("fake-access");
        account.setClientTokenForMigration("fake-client-token");

        assertThat(this.provider.canReuseToken(account)).isEqualTo(TokenReuse.YES);

        assertThat(this.gateway.urls).containsExactly(API_ROOT + "/authserver/validate");
        assertThat(this.gateway.body(0).get("accessToken").getAsString()).isEqualTo("fake-access");
        assertThat(this.gateway.body(0).get("clientToken").getAsString()).isEqualTo("fake-client-token");
    }

    @Test
    void canReuseToken_errorBody_returnsFalse() throws Exception {
        JsonObject error = new JsonObject();
        error.addProperty("error", "ForbiddenOperationException");
        this.gateway.response = error;

        assertThat(this.provider.canReuseToken(account("fake-access"))).isEqualTo(TokenReuse.NO);
    }

    @Test
    void canReuseToken_withoutToken_skipsNetwork() throws Exception {
        assertThat(this.provider.canReuseToken(account(null))).isEqualTo(TokenReuse.NO);
        assertThat(this.gateway.urls).isEmpty();
    }

    @Test
    void signOut_postsInvalidateWithToken() {
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = account("fake-access");
        account.setClientTokenForMigration("fake-client-token");

        this.provider.signOut(account);

        assertThat(this.gateway.urls).containsExactly(API_ROOT + "/authserver/invalidate");
        assertThat(this.gateway.body(0).get("accessToken").getAsString()).isEqualTo("fake-access");
        assertThat(this.gateway.body(0).get("clientToken").getAsString()).isEqualTo("fake-client-token");
    }

    @Test
    void signOut_networkFailure_isSwallowed() {
        // 账号已经删了：撤销失败只能留个日志，绝不能把异常抛回删除链路。
        this.gateway.offline = true;
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = account("fake-access");

        assertThatCode(() -> this.provider.signOut(account)).doesNotThrowAnyException();
        assertThat(this.gateway.urls).hasSize(1);
    }

    @Test
    void signOut_withoutToken_skipsNetwork() {
        this.provider.signOut(account(null));
        assertThat(this.gateway.urls).isEmpty();
    }

    /** 带完整 OAuth 端点（含 userinfo）的注册表：信封账号的复用判定要走 userinfo。 */
    private void installRegistryWithUserinfo() {
        AuthServerRegistry registry = new AuthServerRegistry(JdkHttpGateway.INSTANCE);
        JsonArray servers = new JsonArray();
        servers.add(new AuthServer(API_ROOT, AuthServerKind.YGGDRASIL, "FakeInjector",
                new OAuthEndpoints("fake-client", API_ROOT + "/oauth/token", null,
                        API_ROOT + "/oauth/userinfo", null, null), 0L).toJson());
        registry.load(servers);
        AbstractInjectorAccountProvider.installRegistry(registry);
    }

    private AuthlibInjectorAccountProvider.AuthlibInjectorAccount envelopeAccount() {
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = account("fake-access");
        account.setLoginTokenForMigration(
                AccountOAuth.envelope("fake-refresh", API_ROOT + "/oauth/token", "fake-client"),
                "11111111-1111-1111-1111-111111111111");
        return account;
    }

    @Test
    void canReuseToken_envelope_userinfoSucceeds_isTrue() throws Exception {
        installRegistryWithUserinfo();
        this.gateway.getResponse = new JsonObject();

        assertThat(this.provider.canReuseToken(envelopeAccount())).isEqualTo(TokenReuse.YES);

        // OIDC 令牌必须问 userinfo（issue 268）；validate 不认识它，绝不能发。
        assertThat(this.gateway.urls).containsExactly(API_ROOT + "/oauth/userinfo");
    }

    @Test
    void canReuseToken_envelope_userinfoRejects_returnsNo() throws Exception {
        installRegistryWithUserinfo();
        // 401 是服务端明确否认：该刷新。
        this.gateway.getUserinfoStatus = 401;

        assertThat(this.provider.canReuseToken(envelopeAccount())).isEqualTo(TokenReuse.NO);
        assertThat(this.gateway.urls).containsExactly(API_ROOT + "/oauth/userinfo");
    }

    @Test
    void canReuseToken_envelope_userinfoServerError_isUnknown() throws Exception {
        installRegistryWithUserinfo();
        // 503：userinfo 端点自己坏了，说明不了令牌的死活；此时刷新会作废别的实例刚换好的令牌。
        this.gateway.getUserinfoStatus = 503;

        assertThat(this.provider.canReuseToken(envelopeAccount())).isEqualTo(TokenReuse.UNKNOWN);
    }

    @Test
    void canReuseToken_envelope_withoutUserinfoEndpoint_isFalse() throws Exception {
        // 注册表条目没有 userinfo 端点（老探测缓存）：无处可问，保守判不可复用且零网络。
        assertThat(this.provider.canReuseToken(envelopeAccount())).isEqualTo(TokenReuse.UNKNOWN);
        assertThat(this.gateway.urls).isEmpty();
    }

    @Test
    void canReuseToken_envelope_freshExpiry_skipsNetwork() throws Exception {
        installRegistryWithUserinfo();
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = envelopeAccount();
        account.setTokenExpiryForLoad(System.currentTimeMillis() + 3_600_000L);

        assertThat(this.provider.canReuseToken(account)).isEqualTo(TokenReuse.YES);
        assertThat(this.gateway.urls).isEmpty();
    }

    @Test
    void canReuseToken_sessionToken_freshExpiry_skipsNetwork() throws Exception {
        // expiresAt 短路对会话账号同样成立（当前恒为 0，但语义上不该再发 validate）。
        AuthlibInjectorAccountProvider.AuthlibInjectorAccount account = account("fake-access");
        account.setClientTokenForMigration("fake-client-token");
        account.setTokenExpiryForLoad(System.currentTimeMillis() + 3_600_000L);

        assertThat(this.provider.canReuseToken(account)).isEqualTo(TokenReuse.YES);
        assertThat(this.gateway.urls).isEmpty();
    }
}
