package top.syshub.accountsx.core.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 网络层抽象（P1.3）。把认证流程对 HTTP 的依赖收敛到这个接口，
 * 使 provider 可在单测中注入假实现（见 {@code JdkHttpGateway} 的生产实现）。
 *
 * <p>所有方法默认校验 HTTP 状态码：非 {@code 2xx} 抛 {@link IOException}，
 * 除非对应方法显式接受 {@code ignoreHttpStatus=true}。</p>
 */
public interface HttpGateway {
    /** GET，校验状态码。 */
    JsonObject get(String url) throws IOException;

    /** GET（带请求头），校验状态码。 */
    JsonObject get(String url, Map<String, String> headers) throws IOException;

    /** POST JSON，校验状态码。 */
    JsonObject postJson(String url, JsonElement body) throws IOException;

    /** POST JSON，可选忽略状态码。 */
    JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) throws IOException;

    /** POST form（application/x-www-form-urlencoded），校验状态码。 */
    JsonObject postForm(String url, Map<String, String> formData) throws IOException;

    /** POST form，可选忽略状态码。 */
    JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) throws IOException;

    /** HEAD，返回原始响应头（大小写无关查找请用 {@code NetworkUtils.getHeaderIgnoreCase}）。 */
    Map<String, List<String>> head(String url) throws IOException;

    /** GET，返回原始响应体字节（用于下载非 JSON 资源，如皮肤 PNG）。校验状态码。 */
    byte[] getBinary(String url) throws IOException;
}
