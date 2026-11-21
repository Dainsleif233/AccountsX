package top.syshub.accountsx.common.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
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

    /**
     * 只读取响应头、不解析响应体的 GET。
     *
     * <p>与 {@link #get(String)} 不同，本方法不校验状态码：服务器地址探测常见 3xx/4xx，
     * 但响应头里的 {@code X-Authlib-Injector-API-Location} 仍是有效信息。
     * 同时返回跟随重定向后的最终 URL——按启动器技术规范，没有 ALI 头时它就是 API 地址。</p>
     */
    Headers getHeaders(String url) throws IOException;

    /** GET，返回原始响应体字节（用于下载非 JSON 资源，如皮肤 PNG）。校验状态码。 */
    byte[] getBinary(String url) throws IOException;

    /**
     * 判定类请求的结果：状态码 + 解析后的响应体。
     *
     * <p>只有「令牌还能不能用」这类问题需要它——这些端点用状态码本身表达结论（204 有效、
     * 401/403 无效、5xx 未知），而普通解析路径把状态码压成一句 "HTTP 500" 就再无差别。</p>
     */
    record Result(int statusCode, JsonObject body) {
        /** 服务端临时故障（5xx / 429 / 408）：这次答复说不清结论。 */
        public boolean isTransient() {
            return statusCode >= 500 || statusCode == 429 || statusCode == 408;
        }

        /** 2xx 且没有错误体：请求成功。 */
        public boolean isSuccess() {
            return statusCode / 100 == 2;
        }
    }

    /**
     * 带状态码的 GET（带请求头），不因状态码抛异常。
     *
     * <p>接口不强制实现：查状态码是给判定路径用的，绝大多数假实现用不到。调用方必须自己
     * 处理 {@link UnsupportedOperationException}（按「问不出来」处理）。</p>
     */
    default Result getWithStatus(String url, Map<String, String> headers) throws IOException {
        throw new UnsupportedOperationException("getWithStatus is not implemented by " + getClass().getName());
    }

    /** 带状态码的 POST JSON，不因状态码抛异常（语义同 {@link #getWithStatus}）。 */
    default Result postJsonWithStatus(String url, JsonElement body) throws IOException {
        throw new UnsupportedOperationException("postJsonWithStatus is not implemented by " + getClass().getName());
    }

    /**
     * {@link #getHeaders(String)} 的结果。
     *
     * @param finalUri 跟随重定向后的最终 URL；规范要求把它当作「当前 URL」参与 ALI 解析
     * @param headers  原始响应头（大小写无关查找请用 {@code NetworkUtils.getHeaderIgnoreCase}）
     */
    record Headers(URI finalUri, Map<String, List<String>> headers) {
    }
}
