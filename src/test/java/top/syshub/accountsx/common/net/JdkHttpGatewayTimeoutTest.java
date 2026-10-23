package top.syshub.accountsx.common.net;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 请求超时回归：没有超时的话，一个「接受连接但永不响应」的对端会让 {@code CLIENT.send} 永久阻塞。
 * 在真实场景里这意味着启动刷新批次永不收尾、其它账号已经轮换的新令牌永不落盘
 * （见 {@code AccountManager} 的保存时机），以及 UI 永远显示「正在操作」。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 起一个「黑洞」端点（故意不写响应），验证：
 * <ul>
 *   <li>黑洞连接不会永久挂住，而是抛出 {@link HttpTimeoutException}（{@link IOException} 子类，
 *       会被 {@code AccountManager.refreshAccount} 的 IOException 分支正常归位状态）；</li>
 *   <li>另一个连通正常的账号不受影响 —— 各请求独立超时。</li>
 * </ul>
 * 完整超时依赖 {@code REQUEST_TIMEOUT}（30s），因此本用例约需 30 秒。</p>
 */
class JdkHttpGatewayTimeoutTest {

    private final CountDownLatch releaseBlackHole = new CountDownLatch(1);

    private HttpServer healthy;
    private HttpServer blackHole;
    private String healthyUrl;
    private String blackHoleUrl;

    @BeforeEach
    void start() throws IOException {
        healthy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        healthy.createContext("/", exchange -> {
            byte[] body = "{\"accessToken\":\"fresh\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        healthy.start();
        healthyUrl = "http://127.0.0.1:" + healthy.getAddress().getPort() + "/authserver/refresh";

        // 黑洞：接受请求、读取请求体，然后一直不回复（直到测试结束）。
        // 用闩锁而不是 sleep：@AfterEach 里 countDown 能让处理线程立刻退出，
        // 否则 HttpServer.stop() 会等慢处理线程，白白拖长整个用例（实测 +30s）。
        blackHole = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        blackHole.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                releaseBlackHole.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        blackHole.start();
        blackHoleUrl = "http://127.0.0.1:" + blackHole.getAddress().getPort() + "/authserver/refresh";
    }

    @AfterEach
    void stop() {
        releaseBlackHole.countDown();
        if (healthy != null) {
            healthy.stop(0);
        }
        if (blackHole != null) {
            blackHole.stop(0);
        }
    }

    @Test
    void hungPeerTimesOutInsteadOfBlockingForever() {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", "x");
        body.addProperty("clientToken", UUID.randomUUID().toString());

        long startedAt = System.currentTimeMillis();
        assertThatThrownBy(() -> JdkHttpGateway.INSTANCE.postJson(blackHoleUrl, body, true))
                .isInstanceOf(HttpTimeoutException.class);
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(elapsedMs)
                .as("超时应在 30 秒左右触发，而不是永久阻塞")
                .isLessThan(45_000L);
    }

    /** 一个对端卡住不影响另一个：每个请求各自超时，健康的账号照常拿到响应。 */
    @Test
    void healthyRequestIsUnaffectedByAHungPeer() throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", "x");

        Thread hung = new Thread(() -> {
            try {
                JdkHttpGateway.INSTANCE.postJson(blackHoleUrl, body, true);
            } catch (IOException expected) {
                // 预期超时；本线程只为制造并发压力。
            }
        });
        hung.setDaemon(true);
        hung.start();

        long startedAt = System.currentTimeMillis();
        JsonObject response = JdkHttpGateway.INSTANCE.postJson(healthyUrl, body, true);
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(response.get("accessToken").getAsString()).isEqualTo("fresh");
        assertThat(elapsedMs).as("健康请求不应被卡住的对端拖慢").isLessThan(5_000L);

        hung.interrupt();
        hung.join(1_000L);
    }
}
