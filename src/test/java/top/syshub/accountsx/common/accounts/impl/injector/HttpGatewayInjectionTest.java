package top.syshub.accountsx.common.accounts.impl.injector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider.AuthlibInjectorAccount;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.net.JdkHttpGateway;
import top.syshub.accountsx.common.task.TaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全网：证明网络层已可注入 —— 认证流程可在单测中用假 {@link HttpGateway} 驱动，
 * 无需真实网络或 MC 适配器。
 *
 * <p>用 {@link AuthlibInjectorAccountProvider#refresh} 的非 OAuth 单 profile 路径：
 * 该路径只调用一次 {@link HttpGateway#postJson(String, JsonElement)} 且不涉及
 * {@code Adapters} / {@code AvatarService}，因此可在假网关下独立验证刷新逻辑与注入缝。</p>
 */
class HttpGatewayInjectionTest {

    private static final String SERVER = "https://ygg.invalid/api/yggdrasil";
    private static final String REFRESH_URL = SERVER + "/authserver/refresh";
    private static final String NEW_TOKEN = "new-injector-token";
    private static final String CLIENT_TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String NEW_NAME = "Steve";
    private static final UUID NEW_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** 只回应 refresh 请求、其余方法一律抛出的假网关。 */
    private static final class FakeHttpGateway implements HttpGateway {
        private String lastUrl;
        private JsonElement lastBody;
        private JsonObject response = refreshResponse();

        String lastUrl() {
            return lastUrl;
        }

        JsonElement lastBody() {
            return lastBody;
        }

        private JsonObject refreshResponse() {
            JsonObject selectedProfile = new JsonObject();
            selectedProfile.addProperty("name", NEW_NAME);
            selectedProfile.addProperty("id", NEW_UUID.toString());
            JsonObject json = new JsonObject();
            json.addProperty("accessToken", NEW_TOKEN);
            json.add("selectedProfile", selectedProfile);
            return json;
        }

        @Override
        public JsonObject get(String url) {
            throw new UnsupportedOperationException("fake: get not expected");
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("fake: get(headers) not expected");
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            return postJson(url, body, false);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            this.lastUrl = url;
            this.lastBody = body;
            return response;
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
    }

    @Test
    void refresh_usesInjectedGateway() throws ExecutionException, InterruptedException {
        FakeHttpGateway fake = new FakeHttpGateway();
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(fake);

        // 非 OAuth 登录令牌（不含 "OAuth " 前缀），走非 OAuth 刷新路径。
        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                "old-injector-token", "OldName", UUID.randomUUID(),
                SERVER, UUID.randomUUID().toString(), null, null, 0L
        );
        account.setClientTokenForMigration(CLIENT_TOKEN);

        // refresh 内的 setLoginProfile/setProfile 要求 worker 线程，故经调度器在 worker 线程上执行。
        TaskScheduler.submitParallel(() -> provider.refresh(account)).get();

        assertThat(account.getLoginToken()).isEqualTo(NEW_TOKEN);
        assertThat(account.getAccountStorage().getPlayerName()).isEqualTo(NEW_NAME);
        assertThat(account.getAccountStorage().getPlayerUUID()).isEqualTo(NEW_UUID);

        // 断言刷新确实打到了正确的 refresh 端点，且 body 同时带旧令牌与 clientToken
        // （drasl 缺 clientToken 一律 403，这是「重启后无法重新登录」的直接原因）。
        assertThat(fake.lastUrl()).isEqualTo(REFRESH_URL);
        assertThat(fake.lastBody().getAsJsonObject().get("accessToken").getAsString())
                .isEqualTo("old-injector-token");
        assertThat(fake.lastBody().getAsJsonObject().get("clientToken").getAsString())
                .isEqualTo(CLIENT_TOKEN);
    }

    @Test
    void refresh_withoutClientToken_failsWithActionableKey() {
        FakeHttpGateway fake = new FakeHttpGateway();
        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(fake);
        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                "old-injector-token", "OldName", UUID.randomUUID(),
                SERVER, UUID.randomUUID().toString(), null, null, 0L
        );

        // 旧版本创建的账号没有 clientToken：不发注定失败的请求，直接给出「重新添加」的提示。
        assertThatThrownBy(() -> provider.refresh(account))
                .isInstanceOf(InjectorAuthException.class)
                .extracting(e -> ((InjectorAuthException) e).getTranslationKey())
                .isEqualTo("accountsx.account.fail.injector_invalid_token");
        assertThat(fake.lastUrl()).isNull();
    }

    /** 刷新响应只有新令牌、没有角色列表：令牌必须落地，档案保留旧值。 */
    @Test
    void refresh_commitsRotatedTokenEvenWithoutProfiles() throws ExecutionException, InterruptedException {
        FakeHttpGateway fake = new FakeHttpGateway();
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", NEW_TOKEN);
        JsonArray empty = new JsonArray();
        body.add("availableProfiles", empty);
        fake.response = body;

        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(fake);
        UUID oldUuid = UUID.randomUUID();
        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                "old-injector-token", "OldName", oldUuid,
                SERVER, oldUuid.toString(), null, null, 0L
        );
        account.setClientTokenForMigration(CLIENT_TOKEN);

        TaskScheduler.submitParallel(() -> provider.refresh(account)).get();

        // 服务端已轮换令牌；角色解析不出来也不能把新令牌丢掉。
        assertThat(account.getLoginToken()).isEqualTo(NEW_TOKEN);
        assertThat(account.getAccountStorage().getPlayerName()).isEqualTo("OldName");
        assertThat(account.getAccountStorage().getPlayerUUID()).isEqualTo(oldUuid);
    }

    /** 角色列表形状异常（未检查异常）同样不能影响令牌落地。 */
    @Test
    void refresh_commitsRotatedTokenEvenWhenProfileParsingFails() throws ExecutionException, InterruptedException {
        FakeHttpGateway fake = new FakeHttpGateway();
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", NEW_TOKEN);
        body.addProperty("availableProfiles", "not-an-array");
        fake.response = body;

        AuthlibInjectorAccountProvider provider = new AuthlibInjectorAccountProvider(fake);
        AuthlibInjectorAccount account = new AuthlibInjectorAccount(
                "old-injector-token", "OldName", UUID.randomUUID(),
                SERVER, UUID.randomUUID().toString(), null, null, 0L
        );
        account.setClientTokenForMigration(CLIENT_TOKEN);

        TaskScheduler.submitParallel(() -> provider.refresh(account)).get();

        assertThat(account.getLoginToken()).isEqualTo(NEW_TOKEN);
    }

    @Test
    void productionGatewayWired() {
        HttpGateway gateway = Platforms.getHttpGateway();
        assertThat(gateway).isNotNull();
        assertThat(gateway).isInstanceOf(JdkHttpGateway.class);
    }
}
