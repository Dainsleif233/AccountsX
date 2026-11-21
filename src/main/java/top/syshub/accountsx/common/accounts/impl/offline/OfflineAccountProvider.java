package top.syshub.accountsx.common.accounts.impl.offline;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.BaseAccount.AccountStorage;
import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;

import java.util.UUID;

public class OfflineAccountProvider implements AccountProvider<OfflineAccount> {
    @Override
    public AccountContext createAccountContext(OfflineAccount account) {
        return null;
    }

    /**
     * 离线建号：玩家名必填，UUID 留空时按玩家名推导（离线 UUID 的标准算法）。
     *
     * @throws IllegalArgumentException 玩家名为空，或 UUID 无法解析
     */
    public OfflineAccount login(String playerName, String playerUuid) {
        if (playerName == null || playerName.isEmpty()) {
            throw new IllegalArgumentException("Player name is required.");
        }
        UUID uuid = playerUuid == null || playerUuid.isEmpty()
                ? AccountUUID.ofPlayerName(playerName)
                : AccountUUID.parse(playerUuid);
        return new OfflineAccount(UUID.randomUUID().toString().replace("-", ""), playerName, uuid);
    }

    /**
     * 离线账号的令牌是本地随机串，服务端从不检验它，因此永远可用。
     *
     * <p>没有这个覆写，离线账号每次启动都会被判「不可复用」，{@link #refresh} 于是生成一个新的
     * 占位令牌并落盘——纯本地账号也会在每次开机时触发一次配置文件写入，把别的实例的改动卷进来。</p>
     */
    @Override
    public TokenReuse canReuseToken(OfflineAccount account) {
        AccountStorage storage = account.getAccountStorage();
        String accessToken = storage.getAccessToken();
        // 还没有令牌（刚建号、或配置里被清空）时确实得跑一次 refresh 生成。
        return accessToken == null || accessToken.isEmpty() ? TokenReuse.NO : TokenReuse.YES;
    }

    @Override
    public void refresh(OfflineAccount account) {
        BaseAccount.AccountStorage s = account.getAccountStorage();

        account.setProfile(
                UUID.randomUUID().toString().replace("-", ""),
                s.getPlayerName(),
                s.getPlayerUUID()
        );
    }
}
