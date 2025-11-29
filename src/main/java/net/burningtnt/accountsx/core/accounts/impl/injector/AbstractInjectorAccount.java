package net.burningtnt.accountsx.core.accounts.impl.injector;

import net.burningtnt.accountsx.core.accounts.BaseAccount;
import net.burningtnt.accountsx.core.accounts.model.AccountType;

import java.util.UUID;

public abstract class AbstractInjectorAccount extends BaseAccount {
    private final String server;

    private volatile String loginToken;

    private volatile String preferredPlayerUUID;

    public AbstractInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, AccountType type, String accountName, String avatar) {
        super(accessToken, playerName, playerUUID, type, accountName, avatar);
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

    public final void setLoginProfile(String loginToken, String preferredPlayerUUID) {
        this.loginToken = loginToken;
        this.preferredPlayerUUID = preferredPlayerUUID;
    }
}
