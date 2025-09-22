package net.burningtnt.accountsx.core.accounts.impl.injector.impl;

import net.burningtnt.accountsx.core.accounts.impl.injector.AbstractInjectorAccount;
import net.burningtnt.accountsx.core.accounts.impl.injector.AbstractInjectorAccountProvider;
import net.burningtnt.accountsx.core.accounts.model.AccountType;

import java.util.UUID;

public final class AuthlibInjectorAccountProvider extends AbstractInjectorAccountProvider<AuthlibInjectorAccountProvider.AuthlibInjectorAccount> {
    public AuthlibInjectorAccountProvider() {
        super("as.account.objects.server_domain", "as.account.objects.user_id", "Authlib-Injector");
    }

    @Override
    protected String transformServerBaseURL(String server) {
        return "https://" + server + "/api/yggdrasil/";
    }

    @Override
    protected AuthlibInjectorAccount createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID) {
        return new AuthlibInjectorAccount(accessToken, playerName, playerUUID, server, preferredPlayerUUID);
    }

    public static class AuthlibInjectorAccount extends AbstractInjectorAccount {
        public AuthlibInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID) {
            super(accessToken, playerName, playerUUID, server, preferredPlayerUUID, AccountType.AUTHLIB_INJECTOR);
        }
    }
}
