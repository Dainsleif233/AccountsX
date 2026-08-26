package top.syshub.accountsx.core.utils;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 现状快照测试：{@link NetworkUtils#readResponse} 的 charset 解析行为。
 * 作为 P1–P3 重构的安全网。
 *
 * <p>通过构造 {@code HttpResponse<byte[]>} 的匿名实现来隔离网络层，
 * 只测试 charset 提取逻辑。</p>
 */
class NetworkUtilsReadResponseTest {

    // ── charset 解析 ──────────────────────────────────────────────────

    /**
     * 无 Content-Type → 默认 UTF-8
     */
    @Test
    void readResponse_utf8ByDefault() throws IOException {
        var response = fakeResponse(200, new byte[]{0x48, 0x69}, Map.of());
        var reader = NetworkUtils.readResponse(response, false);
        assertThat(reader.read()).isEqualTo('H');
        assertThat(reader.read()).isEqualTo('i');
    }

    /**
     * Content-Type: application/json; charset=UTF-8 → UTF-8
     */
    @Test
    void readResponse_charsetFromContentType() throws IOException {
        var response = fakeResponse(200, "Hello".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", List.of("application/json; charset=UTF-8")));
        var reader = NetworkUtils.readResponse(response, false);
        assertThat(reader.read()).isEqualTo('H');
    }

    /**
     * charset=GBK 正确解析为 "你好"
     */
    @Test
    void readResponse_charsetGbk() throws IOException {
        // "你好" in GBK: 0xC4E3 0xBAC3
        byte[] gbkBytes = "你好".getBytes(Charset.forName("GBK"));
        var response = fakeResponse(200, gbkBytes,
                Map.of("Content-Type", List.of("text/html; charset=GBK")));
        var reader = NetworkUtils.readResponse(response, false);
        char[] buf = new char[2];
        int n = reader.read(buf);
        // readResponse 用 GBK charset 解码，结果应为 "你好"（两个字符）
        assertThat(n).isEqualTo(2);
        assertThat(new String(buf)).isEqualTo("你好");
    }

    /**
     * charset="UTF-8"（带引号）→ UTF-8
     */
    @Test
    void readResponse_charsetQuoted() throws IOException {
        var response = fakeResponse(200, "OK".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", List.of("application/json; charset=\"UTF-8\"")));
        var reader = NetworkUtils.readResponse(response, false);
        assertThat(reader.read()).isEqualTo('O');
    }

    /**
     * charset=UTF-8; boundary=... → UTF-8（截断到分号）
     */
    @Test
    void readResponse_charsetWithTrailingSemicolon() throws IOException {
        var response = fakeResponse(200, "X".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", List.of("multipart/form-data; charset=UTF-8; boundary=abc")));
        var reader = NetworkUtils.readResponse(response, false);
        assertThat(reader.read()).isEqualTo('X');
    }

    /**
     * charset=INVALID → fallback 到 UTF-8
     */
    @Test
    void readResponse_invalidCharset_fallbackToUtf8() throws IOException {
        var response = fakeResponse(200, "Test".getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", List.of("text/plain; charset=NOT_A_REAL_CHARSET")));
        var reader = NetworkUtils.readResponse(response, false);
        assertThat(reader.read()).isEqualTo('T');
    }

    // ── HTTP 状态码 ───────────────────────────────────────────────────

    /**
     * status 4xx → IOException("HTTP 404")
     */
    @Test
    void readResponse_httpError_throws() {
        var response = fakeResponse(404, new byte[0], Map.of());
        assertThatThrownBy(() -> NetworkUtils.readResponse(response, false))
                .isInstanceOf(IOException.class)
                .hasMessage("HTTP 404");
    }

    /**
     * status 4xx + ignoreHttpStatus=true → 不抛异常
     */
    @Test
    void readResponse_ignoreHttpStatus_noThrow() throws IOException {
        var response = fakeResponse(500, "error".getBytes(StandardCharsets.UTF_8), Map.of());
        var reader = NetworkUtils.readResponse(response, true);
        assertThat(reader.read()).isEqualTo('e');
    }

    /**
     * status 200 → 正常返回
     */
    @Test
    void readResponse_2xx_ok() throws IOException {
        var response = fakeResponse(200, "OK".getBytes(StandardCharsets.UTF_8), Map.of());
        var reader = NetworkUtils.readResponse(response, false);
        assertThat(reader.read()).isEqualTo('O');
        assertThat(reader.read()).isEqualTo('K');
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    /**
     * 构造一个最小化的 HttpResponse 实现，只提供 statusCode、headers 和 body。
     * 避免依赖 HttpClient 实际发送请求。
     */
    private static java.net.http.HttpResponse<byte[]> fakeResponse(
            int statusCode, byte[] body, Map<String, List<String>> headers) {
        // 构建 HttpHeaders（Java 17 无 publicBuilder，用 of 后合并）
        java.net.http.HttpHeaders httpHeaders;
        if (headers.isEmpty()) {
            httpHeaders = java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
        } else {
            var builder = new java.util.HashMap<String, java.util.List<String>>();
            headers.forEach((k, v) -> builder.merge(k, v, (a, b) -> {
                var merged = new java.util.ArrayList<>(a);
                merged.addAll(b);
                return merged;
            }));
            httpHeaders = java.net.http.HttpHeaders.of(builder, (a, b) -> true);
        }

        return new java.net.http.HttpResponse<>() {
            @Override public int statusCode() { return statusCode; }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.net.http.HttpHeaders headers() { return httpHeaders; }
            @Override public byte[] body() { return body; }
            @Override public java.net.URI uri() { return java.net.URI.create("http://test"); }
            @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<java.net.http.HttpResponse<byte[]>> previousResponse() { return java.util.Optional.empty(); }
        };
    }
}
