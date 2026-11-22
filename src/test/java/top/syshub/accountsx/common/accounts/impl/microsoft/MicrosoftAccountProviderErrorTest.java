package top.syshub.accountsx.common.accounts.impl.microsoft;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.net.HttpGateway;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌端点错误体的可读性：微软侧 refresh 与设备码轮询都要能解析
 * RFC 6749 §5.2 的 {@code error} / {@code error_description}，且 error 非
 * 字符串时给出「不可读」消息而不是 UnsupportedOperationException。
 */
class MicrosoftAccountProviderErrorTest {

    private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

    /** 只会答 token 端点的假网关：脚本化每次 postForm 的返回，记录 URL。 */
    private static final class ScriptedGateway implements HttpGateway {
        private final List<String> urls = new ArrayList<>();
        private final List<JsonObject> replies;
        private int cursor;

        ScriptedGateway(JsonObject... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            this.urls.add(url);
            return this.replies.get(this.cursor++);
        }

        @Override public JsonObject get(String url) { throw new UnsupportedOperationException(); }
        @Override public JsonObject get(String url, Map<String, String> headers) { throw new UnsupportedOperationException(); }
        @Override public JsonObject postJson(String url, com.google.gson.JsonElement body) { throw new UnsupportedOperationException(); }
        @Override public JsonObject postJson(String url, com.google.gson.JsonElement body, boolean ignoreHttpStatus) { throw new UnsupportedOperationException(); }
        @Override public JsonObject postForm(String url, Map<String, String> formData) { throw new UnsupportedOperationException(); }
        @Override public HttpGateway.Headers getHeaders(String url) { throw new UnsupportedOperationException(); }
        @Override public byte[] getBinary(String url) { throw new UnsupportedOperationException(); }
    }

    private static JsonObject errorBody(String error, String description) {
        JsonObject token = new JsonObject();
        token.addProperty("error", error);
        token.addProperty("error_description", description);
        return token;
    }

    @Test
    void refresh_invalidGrant_messageIsReadable() {
        ScriptedGateway http = new ScriptedGateway(
                errorBody("invalid_grant", "token has been revoked"));
        MicrosoftAccountProvider provider = new MicrosoftAccountProvider(http);
        MicrosoftAccount account = new MicrosoftAccount("fake-access", "Steve",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "fake-ms-access", "fake-ms-refresh", null, 0L);

        assertThatThrownBy(() -> provider.refresh(account))
                .isInstanceOf(IOException.class)
                .hasMessage("Token endpoint rejected the request: invalid_grant (token has been revoked)");

        // refresh 的第一步就是换 MSA 令牌；失败必须发生在碰 Xbox 链路之前。
        org.assertj.core.api.Assertions.assertThat(http.urls).containsExactly(TOKEN_URL);
    }

    @Test
    void refresh_nonStringError_isUnreadable() {
        JsonObject token = new JsonObject();
        token.add("error", new JsonObject()); // error 不是字符串（不符合规范）
        ScriptedGateway http = new ScriptedGateway(token);
        MicrosoftAccountProvider provider = new MicrosoftAccountProvider(http);
        MicrosoftAccount account = new MicrosoftAccount("fake-access", "Steve",
                java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "fake-ms-access", "fake-ms-refresh", null, 0L);

        assertThatThrownBy(() -> provider.refresh(account))
                .isInstanceOf(IOException.class)
                .hasMessage("Token endpoint returned an unreadable error.");
    }

    @Test
    void loginDeviceCode_unreadableError_isActionable() {
        JsonObject token = new JsonObject();
        token.add("error", new com.google.gson.JsonArray()); // 不是字符串也不该炸
        ScriptedGateway http = new ScriptedGateway(token);
        MicrosoftAccountProvider provider = new MicrosoftAccountProvider(http);
        // interval=0：postForm 前 sleep(0)，测试不需要真的等。
        MicrosoftAccountProvider.DeviceCode code = new MicrosoftAccountProvider.DeviceCode(
                "fake-device-code", "FAKE-CODE", "https://fake.example/activate", 0, 1);

        assertThatThrownBy(() -> provider.loginDeviceCode(code, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessage("Token endpoint returned an unreadable error.");
    }

    @Test
    void loginDeviceCode_accessDenied_isActionable() {
        ScriptedGateway http = new ScriptedGateway(
                errorBody("access_denied", "user cancelled in browser"));
        MicrosoftAccountProvider provider = new MicrosoftAccountProvider(http);
        MicrosoftAccountProvider.DeviceCode code = new MicrosoftAccountProvider.DeviceCode(
                "fake-device-code", "FAKE-CODE", "https://fake.example/activate", 0, 1);

        assertThatThrownBy(() -> provider.loginDeviceCode(code, () -> false))
                .isInstanceOf(IOException.class)
                .hasMessage("Unknown error: access_denied");
    }
}
