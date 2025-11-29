package net.burningtnt.accountsx.core.accounts.impl.env;

import net.burningtnt.accountsx.core.accounts.model.AccountType;
import net.burningtnt.accountsx.core.accounts.BaseAccount;
import net.burningtnt.accountsx.core.utils.AvatarUtils;

import java.util.UUID;

public final class EnvironmentAccount extends BaseAccount {
    public EnvironmentAccount(String accessToken, String playerName, UUID playerUUID) {
        super(
                accessToken,
                playerName,
                playerUUID,
                AccountType.ENV_DEFAULT,
                null,
                AvatarUtils.getAvatar("https://sessionserver.mojang.com" + "/session/minecraft/profile/", playerUUID.toString())
        );
    }
}
