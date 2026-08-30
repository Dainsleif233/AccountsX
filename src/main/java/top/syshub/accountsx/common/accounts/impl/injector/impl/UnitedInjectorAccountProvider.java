package top.syshub.accountsx.common.accounts.impl.injector.impl;

import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.net.HttpGateway;

import java.util.BitSet;
import java.util.UUID;

public final class UnitedInjectorAccountProvider extends AbstractInjectorAccountProvider<UnitedInjectorAccountProvider.UnitedInjectorAccount> {
    public UnitedInjectorAccountProvider(HttpGateway http) {
        super("accountsx.account.objects.server_id", "accountsx.account.objects.user_name", "United-Injector", http);
    }

    private static final BitSet SAFE_SERVER_ID = new BitSet(128);

    static {
        SAFE_SERVER_ID.set('a', 'z' + 1);
        SAFE_SERVER_ID.set('A', 'Z' + 1);
        SAFE_SERVER_ID.set('0', '9' + 1);
    }

    @Override
    protected void validateServerBaseURL(String server) throws IllegalArgumentException {
        int length = server.length();
        if (length != 32) {
            throw new IllegalArgumentException("Server ID must be 32 characters long: " + server);
        }
        for (int i = 0; i < length; i++) {
            char c = server.charAt(i);
            if (c >= 128 || !SAFE_SERVER_ID.get(c)) {
                throw new IllegalArgumentException("Server ID must only contains a-z, A-Z, 0-9.");
            }
        }
    }

    @Override
    protected String transformServerBaseURL(String server) {
        return "https://auth.mc-user.com:233/" + server;
    }

    @Override
    // 1.16 修复：保留 accountName，使联合通行证账号在列表里显示服务器名而非类型名
    protected UnitedInjectorAccount createAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID,String accountName, String avatarKey, long avatarCachedAt) {
        return new UnitedInjectorAccount(accessToken, playerName, playerUUID, server, preferredPlayerUUID, accountName, avatarKey, avatarCachedAt);
    }

    public static class UnitedInjectorAccount extends AbstractInjectorAccount {
        public UnitedInjectorAccount(String accessToken, String playerName, UUID playerUUID, String server, String preferredPlayerUUID, String accountName, String avatarKey, long avatarCachedAt) {
            super(accessToken, playerName, playerUUID, server, preferredPlayerUUID, AccountType.UNITED_INJECTOR, accountName, avatarKey, avatarCachedAt);
        }
    }
}
