package top.syshub.accountsx.common.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * {@link HttpGateway} 的 JDK 实现（P1.3），基于 {@code java.net.http.HttpClient}。
 * 单例 {@link #INSTANCE} 即生产环境网关；测试用假实现直接实现接口即可。
 *
 * <p>序列化统一使用 {@link NetworkUtils#GSON}（请求体序列化与响应体反序列化同一实例），
 * 避免再维护一份配置漂移的 Gson。</p>
 */
public final class JdkHttpGateway implements HttpGateway {
    /**
     * 单个请求（连接 + 响应）的上限。
     *
     * <p>没有超时时，一个不响应的认证服务器会把刷新任务永久占住：启动批次的
     * {@code runParallel} 永远不回调，UI 上的账号一直停在「正在登录」，而且并发的
     * 后续刷新会被去重逻辑一直等待，整条刷新链路就此卡死。</p>
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // 必须声明在常量之后：静态初始化按源码顺序执行，放在前面会拿到 null 超时。
    public static final JdkHttpGateway INSTANCE = new JdkHttpGateway();

    private final Duration requestTimeout;

    private JdkHttpGateway() {
        this(REQUEST_TIMEOUT);
    }

    /** 测试用：以更短的超时构造，便于在本地起一个延迟响应的服务端验证超时行为。 */
    JdkHttpGateway(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject get(String url) throws IOException {
        return get(url, Map.of());
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject get(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET();
        headers.forEach(builder::header);
        return sendJson(builder.build(), false);
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject postJson(String url, JsonElement body) throws IOException {
        return postJson(url, body, false);
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NetworkUtils.GSON.toJson(body)))
                .build();
        return sendJson(request, ignoreHttpStatus);
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject postForm(String url, Map<String, String> formData) throws IOException {
        return postForm(url, formData, false);
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(formData)))
                .build();
        return sendJson(request, ignoreHttpStatus);
    }

    /** {@inheritDoc} */
    @Override
    public Headers getHeaders(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            // 用 GET 而非 HEAD：部分服务器对 HEAD 直接 405/404，且这里需要跟随重定向后的最终 URL。
            return new Headers(response.uri(), response.headers().map());
        } catch (HttpTimeoutException e) {
            throw new IOException("Request to " + url + " timed out after " + requestTimeout.toSeconds() + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public byte[] getBinary(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET().build();
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            // 校验状态码：非 2xx 抛 IOException，与 JSON 路径一致。
            int statusCode = response.statusCode();
            if (statusCode / 100 != 2) {
                throw new IOException("HTTP " + statusCode);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Result getWithStatus(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET();
        headers.forEach(builder::header);
        return sendWithStatus(builder.build());
    }

    /** {@inheritDoc} */
    @Override
    public Result postJsonWithStatus(String url, JsonElement body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NetworkUtils.GSON.toJson(body)))
                .build();
        return sendWithStatus(request);
    }

    /**
     * 发一次请求并把状态码原样交回，不做 2xx 校验。
     *
     * <p>判定端点（{@code /authserver/validate}、userinfo）用状态码本身表达结论，把它们压成
     * "HTTP 500" 吞掉之后，调用方就再也分不清「令牌死了」和「服务端坏了」。</p>
     */
    private Result sendWithStatus(HttpRequest request) throws IOException {
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            JsonObject parsed = null;
            try (Reader reader = NetworkUtils.readResponse(response, true)) {
                JsonElement element = NetworkUtils.GSON.fromJson(reader, JsonElement.class);
                parsed = element instanceof JsonObject object ? object : null;
            } catch (com.google.gson.JsonParseException ignored) {
                // 判定端点正常时本来就没有响应体（204）；解析不出 JSON 不等于请求失败。
            }
            return new Result(response.statusCode(), parsed);
        } catch (HttpTimeoutException e) {
            throw new IOException("Request to " + request.uri() + " timed out after " + requestTimeout.toSeconds() + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    private JsonObject sendJson(HttpRequest request, boolean ignoreHttpStatus) throws IOException {
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            try (Reader reader = NetworkUtils.readResponse(response, ignoreHttpStatus)) {
                return NetworkUtils.GSON.fromJson(reader, JsonObject.class);
            }
        } catch (HttpTimeoutException e) {
            // 单独成句：刷新失败时用户/作者能一眼看出是服务端不响应，而不是「登录失败」。
            throw new IOException("Request to " + request.uri() + " timed out after " + requestTimeout.toSeconds() + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    private static String encodeForm(Map<String, String> formData) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : formData.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
