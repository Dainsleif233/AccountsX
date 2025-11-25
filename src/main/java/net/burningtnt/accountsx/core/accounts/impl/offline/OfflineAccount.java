package net.burningtnt.accountsx.core.accounts.impl.offline;

import net.burningtnt.accountsx.core.accounts.model.AccountType;
import net.burningtnt.accountsx.core.accounts.BaseAccount;

import java.util.UUID;

public final class OfflineAccount extends BaseAccount {
    public OfflineAccount(String accessToken, String playerName, UUID playerUUID) {
        super(accessToken, playerName, playerUUID, AccountType.OFFLINE, null, null);
    }
}
