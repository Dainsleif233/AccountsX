package top.syshub.accountsx.common.accounts.impl.injector;

import java.io.IOException;

/**
 * 外置登录（Yggdrasil / authlib-injector）刷新或登录被服务端拒绝。
 *
 * <p>单独成类是为了让 UI 能把「凭据已失效，请重新添加账号」这类可操作提示告诉用户，
 * 而不是落进 {@code accountsx.account.fail.unknown}（原实现把服务端返回的
 * {@code errorMessage} 直接丢进日志，用户只看到「未知错误」）。</p>
 *
 * <p>{@link #getMessage()} 只携带服务端返回的 {@code error} / {@code errorMessage}，
 * 绝不包含 accessToken / clientToken 等凭据。</p>
 */
public final class InjectorAuthException extends IOException {
    private final String translationKey;

    public InjectorAuthException(String translationKey, String message) {
        super(message);
        this.translationKey = translationKey;
    }

    /** 供 {@code TaskScheduler} 映射 toast 文案的 i18n key。 */
    public String getTranslationKey() {
        return translationKey;
    }
}
