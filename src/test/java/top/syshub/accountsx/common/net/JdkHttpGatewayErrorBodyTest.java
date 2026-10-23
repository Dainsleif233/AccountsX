package top.syshub.accountsx.common.net;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 {@link JdkHttpGateway} 的环回测试：用 JDK 自带 {@code HttpServer} 起一个本地端点，
 * 校验「非 2xx 的响应体能否读到」这一行为 —— 它无法用假网关覆盖（假网关不经过
 * {@link JdkHttpGateway} 与 {@code NetworkUtils.readResponse}）。
 *
 * <p>背景：Yggdrasil 的认证失败是标准 JSON 错误体（drasl 一律 403 + error/errorMessage）。
 * 若网关在非 2xx 时只抛 {@code IOException("HTTP 403")}，provider 就拿不到服务端原因，
 * 用户只能看到「未知错误」。因此 provider 必须用 {@code ignoreHttpStatus=true} 发送认证请求。</p>
 */
class JdkHttpGatewayErrorBodyTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/authserver/refresh", exchange -> {
            byte[] body = ("{\"path\":\"/authserver/refresh\",\"error\":\"ForbiddenOperationException\","
                    + "\"errorMessage\":\"Invalid token\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(403, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        // 模拟 drasl 的 500：只有 errorMessage，没有 error 字段。
        server.createContext("/authserver/broken", exchange -> {
            byte[] body = "{\"path\":\"/authserver/broken\",\"errorMessage\":\"internal server error\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 默认行为：非 2xx 抛 IOException，且错误体不可达（这正是 provider 必须忽略状态码的原因）。 */
    @Test
    void defaultMode_throwsAndLosesTheErrorBody() {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", "x");

        assertThatThrownBy(() -> JdkHttpGateway.INSTANCE.postJson(baseUrl + "/authserver/refresh", body))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }

    /** ignoreHttpStatus=true：拿到 403 的错误体，provider 才能给出可操作提示。 */
    @Test
    void ignoreHttpStatus_readsTheErrorBody() throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", "x");

        JsonObject response = JdkHttpGateway.INSTANCE.postJson(baseUrl + "/authserver/refresh", body, true);

        assertThat(response.get("error").getAsString()).isEqualTo("ForbiddenOperationException");
        assertThat(response.get("errorMessage").getAsString()).isEqualTo("Invalid token");
    }

    /** 500 且没有 error 字段：仍能拿到 JSON（provider 侧由 requireAccessToken 兜底）。 */
    @Test
    void ignoreHttpStatus_readsBodyWithoutErrorField() throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", "x");

        JsonObject response = JdkHttpGateway.INSTANCE.postJson(baseUrl + "/authserver/broken", body, true);

        assertThat(response.has("error")).isFalse();
        assertThat(response.get("errorMessage").getAsString()).isEqualTo("internal server error");
    }
}
