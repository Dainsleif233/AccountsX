package net.burningtnt.accountsx.core.accounts.impl.microsoft;

import net.burningtnt.accountsx.core.accounts.model.AccountType;
import net.burningtnt.accountsx.core.accounts.BaseAccount;

import java.util.UUID;

public final class MicrosoftAccount extends BaseAccount {
    private volatile String microsoftAccountAccessToken;

    private volatile String microsoftAccountRefreshToken;

    public MicrosoftAccount(String accessToken, String playerName, UUID playerUUID, String microsoftAccountAccessToken, String microsoftAccountRefreshToken, String avatar) {
        super(accessToken, playerName, playerUUID, AccountType.MICROSOFT, null, avatar);
        this.microsoftAccountAccessToken = microsoftAccountAccessToken;
        this.microsoftAccountRefreshToken = microsoftAccountRefreshToken;
    }

    public String getMicrosoftAccountAccessToken() {
        return microsoftAccountAccessToken;
    }

    public String getMicrosoftAccountRefreshToken() {
        return microsoftAccountRefreshToken;
    }

    public void setMicrosoftAccountToken(String microsoftAccountAccessToken, String microsoftAccountRefreshToken) {
        this.microsoftAccountAccessToken = microsoftAccountAccessToken;
        this.microsoftAccountRefreshToken = microsoftAccountRefreshToken;
    }
}
