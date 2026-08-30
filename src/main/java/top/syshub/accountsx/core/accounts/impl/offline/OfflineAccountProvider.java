package top.syshub.accountsx.core.accounts.impl.offline;

import top.syshub.accountsx.core.accounts.AccountProvider;
import top.syshub.accountsx.core.accounts.AccountUUID;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.model.context.AccountContext;
import top.syshub.accountsx.core.net.HttpGateway;
import top.syshub.accountsx.core.ui.Memory;
import top.syshub.accountsx.core.ui.UIScreen;

import java.util.Objects;
import java.util.UUID;

public class OfflineAccountProvider implements AccountProvider<OfflineAccount> {
    private static final String GUID_PLAYER_NAME = "guid:as.login.offline.widgets.player_name";
    private static final String GUID_PLAYER_UUID = "guid:as.login.offline.widgets.player_uuid";

    // 离线账号不发起网络请求；构造器接收网关仅为统一所有 provider 的构造形态（P1.3）。
    // 即便不使用，也校验注入的网关非空，与依赖注入契约保持一致。
    public OfflineAccountProvider(HttpGateway http) {
        Objects.requireNonNull(http, "http gateway");
    }

    @Override
    public AccountContext createAccountContext(OfflineAccount account) {
        return null;
    }

    @Override
    public void configure(UIScreen screen) {
        screen.setTitle("accountsx.account.general.login");
        screen.putTextInput(GUID_PLAYER_NAME, "accountsx.account.objects.player_name");
        screen.putTextInput(GUID_PLAYER_UUID, "accountsx.account.objects.player_uuid");
    }

    @Override
    public int validate(UIScreen screen, Memory memory) throws IllegalArgumentException {
        String playerName = screen.getTextInput(GUID_PLAYER_NAME);
        memory.set(GUID_PLAYER_NAME, playerName);

        String playerUUIDString = screen.getTextInput(GUID_PLAYER_UUID);
        if (playerUUIDString.isEmpty()) {
            memory.set(GUID_PLAYER_UUID, AccountUUID.ofPlayerName(playerName));
        } else {
            try {
                memory.set(GUID_PLAYER_UUID, AccountUUID.parse(playerUUIDString));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Cannot parse current UUID: " + playerUUIDString);
            }
        }

        return STATE_IMMEDIATE_CLOSE;
    }

    @Override
    public OfflineAccount login(Memory memory) {
        return new OfflineAccount(
                UUID.randomUUID().toString().replace("-", ""),
                memory.get(GUID_PLAYER_NAME, String.class),
                memory.get(GUID_PLAYER_UUID, UUID.class)
        );
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
