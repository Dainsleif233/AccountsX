package top.syshub.accountsx.core.accounts.impl.injector;

import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.utils.Threading;

import java.util.UUID;

public abstract class AbstractInjectorAccount extends BaseAccount {
    private final String server;

    private volatile String loginToken;

    private volatile String preferredPlayerUUID;

    public AbstractInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, AccountType type, String accountName, String avatarKey, long avatarCachedAt) {
        super(accessToken, playerName, playerUUID, type, accountName, avatarKey, avatarCachedAt);
        this.server = server;
        this.loginToken = accessToken;
        this.preferredPlayerUUID = preferredPlayerUUID;
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

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setLoginProfile(String loginToken, String preferredPlayerUUID) {
        Threading.checkAccountWorkerThread();
        this.loginToken = loginToken;
        this.preferredPlayerUUID = preferredPlayerUUID;
    }
}
