package top.syshub.accountsx.common.accounts;

import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;

import java.io.IOException;

/**
 * 账号类型的运行时能力：建 authlib 上下文、刷新资料。
 *
 * <p>建号入口不在这里——每种类型的登录流程差别太大（密码、浏览器设备码、OAuth 轮询、纯本地），
 * 各自的 provider 暴露自己的方法，由专用界面直接调用（外置认证界面调 {@code loginPassword} /
 * {@code loginDeviceCode}，微软界面调 {@code requestDeviceCode} / {@code loginDeviceCode}）。</p>
 */
public interface AccountProvider<T extends BaseAccount> {
    AccountContext createAccountContext(T account) throws IOException;

    void refresh(T account) throws IOException;

    /**
     * 现有令牌是否仍然可用——可用则调用方跳过 {@link #refresh}，避免无谓的令牌轮换。
     *
     * <p>为什么值得多一次请求：Yggdrasil 的 refresh 会让服务端作废旧 accessToken（drasl 递增版本号），
     * 而账号的 {@code state} 是 transient 的，每次启动都按「未授权」处理。若启动时无条件 refresh，
     * 同一账号每启动一次就换一次令牌，同账号在别的客户端/实例上会立刻 401。</p>
     *
     * <p><b>三态而不是布尔：</b>「确定无效」和「问不出来」是两件事。前者该刷新，后者该<b>放弃本轮</b>
     * ——探测失败（网络抖动、网关超时、服务端 5xx）时令牌很可能仍然是好的，此时刷新会当场作废
     * 另一个实例刚换好的令牌，把一次网络故障升级成账号互踢。所以实现无法判定时返回
     * {@link TokenReuse#UNKNOWN}；只有实现自己判断不了的意外异常才抛 {@link IOException}，
     * 调用方同样按「问不出来」处理。</p>
     */
    default TokenReuse canReuseToken(T account) throws IOException {
        return TokenReuse.NO;
    }

    /**
     * 账号被删除时尽力通知服务端作废令牌。
     *
     * <p>删除是用户已经看到的既成事实，这里只做收尾，因此实现必须自己吞掉所有异常——网络失败
     * 不能让删除回滚或让调用方看到报错。没有撤销端点的类型保持空实现（本地凭据随账号一起消失）。</p>
     */
    default void signOut(T account) {
    }

    @SuppressWarnings("unchecked")
    static <T extends BaseAccount> AccountProvider<T> getProvider(T account) {
        return (AccountProvider<T>) account.getAccountType().getAccountProvider();
    }
}
