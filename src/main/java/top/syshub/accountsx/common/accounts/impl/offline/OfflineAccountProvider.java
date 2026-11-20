package top.syshub.accountsx.common.accounts.impl.offline;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.BaseAccount;
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
