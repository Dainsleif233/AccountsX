package top.syshub.accountsx.common.accounts.impl.offline;

import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.BaseAccount;

import java.util.UUID;

public final class OfflineAccount extends BaseAccount {
    public OfflineAccount(String accessToken, String playerName, UUID playerUUID) {
        super(accessToken, playerName, playerUUID, AccountType.OFFLINE, null, null, 0L);
    }
}
