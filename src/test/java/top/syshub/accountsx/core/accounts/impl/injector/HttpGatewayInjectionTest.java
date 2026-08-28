package top.syshub.accountsx.core.accounts.impl.injector;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.accounts.impl.injector.impl.UnitedInjectorAccountProvider;
import top.syshub.accountsx.core.accounts.impl.injector.impl.UnitedInjectorAccountProvider.UnitedInjectorAccount;
import top.syshub.accountsx.core.adapters.Adapters;
import top.syshub.accountsx.core.net.HttpGateway;
import top.syshub.accountsx.core.net.JdkHttpGateway;
import top.syshub.accountsx.core.task.TaskScheduler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.3 安全网：证明网络层已可注入 —— 认证流程可在单测中用假 {@link HttpGateway} 驱动，
 * 无需真实网络或 MC 适配器。
 *
 * <p>用 {@link UnitedInjectorAccountProvider#refresh} 的非 OAuth 单 profile 路径：
 * 该路径只调用一次 {@link HttpGateway#postJson(String, JsonElement)} 且不涉及
 * {@code Adapters} / {@code AvatarUtils}，因此可在假网关下独立验证刷新逻辑与注入缝。</p>
 */
class HttpGatewayInjectionTest {

    private static final String SERVER = "https://auth.mc-user.com:233/fake0000000000000000000000abcd";
    private static final String REFRESH_URL = SERVER + "/authserver/refresh";
    private static final String NEW_TOKEN = "new-injector-token";
    private static final String NEW_NAME = "Steve";
    private static final UUID NEW_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    /** 只回应 refresh 请求、其余方法一律抛出的假网关。 */
    private static final class FakeHttpGateway implements HttpGateway {
        private String lastUrl;
        private JsonElement lastBody;

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
            return refreshResponse();
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
        public Map<String, List<String>> head(String url) {
            throw new UnsupportedOperationException("fake: head not expected");
        }
    }

    @Test
    void refresh_usesInjectedGateway() throws ExecutionException, InterruptedException {
        FakeHttpGateway fake = new FakeHttpGateway();
        UnitedInjectorAccountProvider provider = new UnitedInjectorAccountProvider(fake);

        // 非 OAuth 登录令牌（不含 "OAuth " 前缀），走非 OAuth 刷新路径。
        UnitedInjectorAccount account = new UnitedInjectorAccount(
                "old-injector-token", "OldName", UUID.randomUUID(),
                SERVER, UUID.randomUUID().toString(), null, null
        );

        // refresh 内的 setLoginProfile/setProfile 要求 worker 线程，故经调度器在 worker 线程上执行。
        TaskScheduler.submitParallel(() -> provider.refresh(account)).get();

        assertThat(account.getLoginToken()).isEqualTo(NEW_TOKEN);
        assertThat(account.getAccountStorage().getPlayerName()).isEqualTo(NEW_NAME);
        assertThat(account.getAccountStorage().getPlayerUUID()).isEqualTo(NEW_UUID);

        // 断言刷新确实打到了正确的 refresh 端点，且 body 带旧令牌。
        assertThat(fake.lastUrl()).isEqualTo(REFRESH_URL);
        assertThat(fake.lastBody().getAsJsonObject().get("accessToken").getAsString())
                .isEqualTo("old-injector-token");
    }

    @Test
    void productionGatewayWired() {
        HttpGateway gateway = Adapters.getHttpGateway();
        assertThat(gateway).isNotNull();
        assertThat(gateway).isInstanceOf(JdkHttpGateway.class);
    }
}
