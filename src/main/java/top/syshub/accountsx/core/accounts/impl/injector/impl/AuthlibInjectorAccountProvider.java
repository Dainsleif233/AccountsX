package top.syshub.accountsx.core.accounts.impl.injector.impl;

import top.syshub.accountsx.core.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.core.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.net.HttpGateway;
import top.syshub.accountsx.core.utils.NetworkUtils;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuthlibInjectorAccountProvider extends AbstractInjectorAccountProvider<AuthlibInjectorAccountProvider.AuthlibInjectorAccount> {
    public AuthlibInjectorAccountProvider(HttpGateway http) {
        super("accountsx.account.objects.server_domain", "accountsx.account.objects.user_id", "Authlib-Injector", http);
    }

    @Override
    protected String transformServerBaseURL(String server) {
        String api = buildUrl(server);
        int redirects = 0;
        while (true) {
            try {
                if (redirects > 10)
                    throw new IOException("Too many redirects (" + redirects + ") while resolving API location, last URL: " + api);
                Map<String, List<String>> headers = http.head(api);
                List<String> apiLocations = NetworkUtils.getHeaderIgnoreCase(headers, "X-Authlib-Injector-API-Location");
                List<String> locations = NetworkUtils.getHeaderIgnoreCase(headers, "Location");
                if (apiLocations != null && !apiLocations.isEmpty()) {
                    String candidate = apiLocations.get(0);
                    String newApi = NetworkUtils.resolveLocation(api, candidate);
                    if (!newApi.equals(api)) {
                        redirects++;
                        api = newApi;
                    } else return api;
                } else if (locations != null && !locations.isEmpty()) {
                    String candidate = locations.get(0);
                    String newApi = NetworkUtils.resolveLocation(api, candidate);
                    if (!newApi.equals(api)) {
                        redirects++;
                        api = newApi;
                    } else return api;
                } else {
                    if (redirects == 0) {
                        URI baseUri = URI.create(api);
                        return baseUri.getScheme() + "://" + baseUri.getAuthority() + "/api/yggdrasil";
                    } else return api;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String buildUrl(String server) {
        try {
            URI u = URI.create(server);
            if (u.getScheme() != null) return server;
            return "https://" + server;
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
