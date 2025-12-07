package net.burningtnt.accountsx.core.accounts.impl.injector.impl;

import net.burningtnt.accountsx.core.accounts.impl.injector.AbstractInjectorAccount;
import net.burningtnt.accountsx.core.accounts.impl.injector.AbstractInjectorAccountProvider;
import net.burningtnt.accountsx.core.accounts.model.AccountType;
import net.burningtnt.accountsx.core.utils.NetworkUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuthlibInjectorAccountProvider extends AbstractInjectorAccountProvider<AuthlibInjectorAccountProvider.AuthlibInjectorAccount> {
    public AuthlibInjectorAccountProvider() {
        super("as.account.objects.server_domain", "as.account.objects.user_id", "Authlib-Injector");
    }

    @Override
    protected String transformServerBaseURL(String server) {
        String api = buildUrl(server);
        int redirects = 0;
        while (true) {
            try {
                Map<String, List<String>> headers = NetworkUtils.headRequest(api);
                List<String> apiLocations = headers.get("X-Authlib-Injector-API-Location");
                if (apiLocations == null || apiLocations.isEmpty()) {
                    if (redirects == 0) return "https://" + server + "/api/yggdrasil/";
                    else return api;
                }
                String newApi = apiLocations.get(0);
                if (!newApi.equals(api)) {
                    redirects++;
                    if (redirects > 10) {
                        throw new IOException("Too many redirects (" + redirects + ") while resolving API location, last URL: " + api);
                    }
                    api = newApi;
                    continue;
                }
                return api;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String buildUrl(String server) {
        try {
            URI u = URI.create(server);
            if (u.getScheme() != null) return server;
            return "http://" + server;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid server URL: " + server, e);
        }
    }

    @Override
    protected AuthlibInjectorAccount createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatar) {
        return new AuthlibInjectorAccount(accessToken, playerName, playerUUID, server, preferredPlayerUUID, accountName, avatar);
    }

    public static class AuthlibInjectorAccount extends AbstractInjectorAccount {
        public AuthlibInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatar) {
            super(accessToken, playerName, playerUUID, server, preferredPlayerUUID, AccountType.AUTHLIB_INJECTOR, accountName, avatar);
        }
    }
}
