package top.syshub.accountsx.core.accounts.impl.env;

import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.utils.AvatarUtils;

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
