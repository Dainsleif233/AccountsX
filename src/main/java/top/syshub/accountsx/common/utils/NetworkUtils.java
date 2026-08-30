package top.syshub.accountsx.common.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import top.syshub.accountsx.common.accounts.AccountUUID;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 网络相关纯工具（P1.3 后仅保留不发送 HTTP 的辅助方法）。
 *
 * <p>P1.3 前本类还包含 {@code buildGet}、{@code headRequest}、{@code postRequest}（多重载）、
 * {@code encodeForm}、{@code CLIENT} 等 HTTP 发送方法，现已迁至
 * {@link top.syshub.accountsx.common.net.JdkHttpGateway}（经 {@link top.syshub.accountsx.common.net.HttpGateway}
 * 抽象后可注入）。本类保留的成员仅用于该实现的底层解析与 provider 的头部处理：</p>
 * <ul>
 *   <li>{@link #GSON}：跨认证/存储共用的序列化实例（带 {@code UUIDTypeAdapter} + pretty printing）</li>
 *   <li>{@link #readResponse}：响应体 charset 解析（{@link top.syshub.accountsx.common.net.JdkHttpGateway} 内部调用）</li>
 *   <li>{@link #getHeaderIgnoreCase}：响应头大小写无关查找（{@code AuthlibInjectorAccountProvider.transformServerBaseURL} 调用）</li>
 *   <li>{@link #resolveLocation}：重定向位置解析（同上）</li>
 * </ul>
 */
public class NetworkUtils {
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(UUID.class, new AccountUUID.UUIDTypeAdapter())
            .setPrettyPrinting()
            .create();

    public static List<String> getHeaderIgnoreCase(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static String resolveLocation(String base, String loc) throws IOException {
        try {
            URI baseUri = URI.create(base);
            URI locUri = URI.create(loc);
            URI next = locUri.isAbsolute() ? locUri : baseUri.resolve(locUri);
            return next.toString();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid redirect location", e);
        }
    }

    public static Reader readResponse(HttpResponse<byte[]> response, boolean ignoreHttpStatus) throws IOException {
        if (!ignoreHttpStatus) {
            int statusCode = response.statusCode();
            if (statusCode / 100 != 2) {
                throw new IOException("HTTP " + statusCode);
            }
        }

        Charset charset = StandardCharsets.UTF_8;
        Optional<String> contentType = response.headers().firstValue("Content-Type");
        if (contentType.isPresent()) {
            String ct = contentType.get().toLowerCase(Locale.ROOT);
            int idx = ct.indexOf("charset=");
            if (idx != -1) {
                String cs = ct.substring(idx + 8).trim();
                int semi = cs.indexOf(';');
                if (semi != -1) cs = cs.substring(0, semi);
                cs = cs.replace("\"", "").trim();
                try {
                    charset = Charset.forName(cs);
                } catch (Exception ignored) {
                }
            }
        }

        return new InputStreamReader(new java.io.ByteArrayInputStream(response.body()), charset);
    }
}
