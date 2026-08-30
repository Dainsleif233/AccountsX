package top.syshub.accountsx.core.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import top.syshub.accountsx.core.utils.NetworkUtils;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
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
    public static final JdkHttpGateway INSTANCE = new JdkHttpGateway();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private JdkHttpGateway() {
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject get(String url) throws IOException {
        return get(url, Map.of());
    }

    /** {@inheritDoc} */
    @Override
    public JsonObject get(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
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
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(formData)))
                .build();
        return sendJson(request, ignoreHttpStatus);
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, List<String>> head(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.headers().map();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public byte[] getBinary(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
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

    private JsonObject sendJson(HttpRequest request, boolean ignoreHttpStatus) throws IOException {
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            try (Reader reader = NetworkUtils.readResponse(response, ignoreHttpStatus)) {
                return NetworkUtils.GSON.fromJson(reader, JsonObject.class);
            }
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
