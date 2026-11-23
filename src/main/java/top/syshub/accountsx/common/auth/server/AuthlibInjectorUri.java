package top.syshub.accountsx.common.auth.server;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 解析 authlib-injector 启动器技术规范里「通过拖拽设置」的文本。
 *
 * <p>规范约定拖入的 text/plain 内容是 {@code authlib-injector:yggdrasil-server:<encodeURIComponent(API地址)>}。
 * 这里只认这个前缀：拖入任意其它文本不应触发加服，以免和别的文本拖拽用途打架。解码后的地址
 * 必须是 http(s) URL，否则视为无效。纯字符串处理，不碰网络，可单测。</p>
 */
public final class AuthlibInjectorUri {

    private static final String PREFIX = "authlib-injector:yggdrasil-server:";

    private AuthlibInjectorUri() {
    }

    /** 命中规范 URI 时返回解码后的 API 根地址，否则返回 null。 */
    public static String parseApiRoot(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith(PREFIX)) {
            return null;
        }
        String encoded = trimmed.substring(PREFIX.length());
        if (encoded.isEmpty()) {
            return null;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法的百分号转义序列：不是有效的规范文本。
            return null;
        }
        decoded = decoded.trim();
        if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
            return decoded;
        }
        return null;
    }
}
