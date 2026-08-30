package top.syshub.accountsx.common.accounts.impl.env;

import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.utils.AvatarService;

import java.util.UUID;

public final class EnvironmentAccount extends BaseAccount {
    public EnvironmentAccount(String accessToken, String playerName, UUID playerUUID) {
        // this(...) 委托：静态方法调用可作为委托参数（source 17 不允许 super 前出现语句，
        // 故用委托把头像 key 计算挪到另一个构造器里）。
        this(accessToken, playerName, playerUUID, avatarOf(playerUUID));
    }

    private EnvironmentAccount(String accessToken, String playerName, UUID playerUUID, AvatarService.AvatarKey avatar) {
        super(
                accessToken,
                playerName,
                playerUUID,
                AccountType.ENV_DEFAULT,
                null,
                avatar == null ? null : avatar.key(),
                avatar == null ? 0L : avatar.cachedAt()
        );
    }

    // 环境账号即当前启动器会话；头像取 Mojang session server 的 profile 皮肤。
    // 失败（离线 / 无网络）时静默返回 null key，UI 回退默认头像（与原行为一致）。
    private static AvatarService.AvatarKey avatarOf(UUID playerUUID) {
        return AvatarService.fetch(
                Platforms.getHttpGateway(),
                "https://sessionserver.mojang.com" + "/session/minecraft/profile/",
                playerUUID.toString()
        );
    }
}
