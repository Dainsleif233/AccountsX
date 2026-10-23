package top.syshub.accountsx.common.accounts.impl.injector;

import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.utils.Threading;

import java.util.UUID;

public abstract class AbstractInjectorAccount extends BaseAccount {
    private final String server;

    private volatile String loginToken;

    private volatile String preferredPlayerUUID;

    /**
     * Yggdrasil 客户端令牌，随账号持久化。
     *
     * <p>Yggdrasil 的 {@code /authserver/refresh} 要求请求里的 {@code clientToken} 与服务端记录的
     * 一致（drasl 在 {@code GetClient} 里强制比对，不匹配即 403 {@code Invalid token}）。若登录时
     * 不主动提供一个固定值，服务端会为每次 {@code /authenticate} 随机生成并在响应里返回，客户端
     * 一旦丢掉它，之后的刷新就再也无法通过校验 —— 这正是「外置登录只有第一次能用」的根因。</p>
     *
     * <p>旧版本写下的账号载荷没有该字段，反序列化后为 {@code null}；由于当时也没有保存登录密码，
     * 无法凭现有数据补出一个能通过校验的值，只能提示用户重新添加账号，见
     * {@link AbstractInjectorAccountProvider#refresh}。</p>
     */
    private volatile String clientToken;

    public AbstractInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, AccountType type, String accountName, String avatarKey, long avatarCachedAt, String clientToken) {
        super(accessToken, playerName, playerUUID, type, accountName, avatarKey, avatarCachedAt);
        this.server = server;
        this.loginToken = accessToken;
        this.preferredPlayerUUID = preferredPlayerUUID;
        this.clientToken = clientToken;
    }

    public final String getServer() {
        return server;
    }

    public final String getLoginToken() {
        return loginToken;
    }

    public final String getPreferredPlayerUUID() {
        return preferredPlayerUUID;
    }

    /**
     * 持久化的 Yggdrasil 客户端令牌；{@code null} 表示该账号由旧版本创建（未保存 clientToken），
     * 必须重新添加才能恢复刷新能力。
     */
    public final String getClientToken() {
        return clientToken;
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setLoginProfile(String loginToken, String preferredPlayerUUID) {
        Threading.checkAccountWorkerThread();
        this.loginToken = loginToken;
        this.preferredPlayerUUID = preferredPlayerUUID;
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setClientToken(String clientToken) {
        Threading.checkAccountWorkerThread();
        this.clientToken = clientToken;
    }
}
