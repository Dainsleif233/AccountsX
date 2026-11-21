package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.ui.Translator;

public final class I18N {
    private I18N() {
    }

    public static final Translator<Component> TRANSLATOR = new Translator<>(Component::translatable);

    /** 标题屏那行「正在使用 …」：外置账号显示所在服务器名，其余显示类型名。 */
    public static Component currentAccount() {
        BaseAccount current = AccountManager.getCurrentAccount();
        return TRANSLATOR.translate(current, AccountManager.serverDisplayName(current));
    }

    /**
     * 列表里账号的来源行：优先用建号时记下的服务器名，取不到就实时查一次
     * （老配置里的外置账号没有 accountName，直接退回类型名会丢掉「在哪个服务器上」）。
     */
    public static Component accountSource(BaseAccount account) {
        String name = account.getAccountName();
        if (name != null && !name.isBlank()) {
            return Component.nullToEmpty(name);
        }
        String server = AccountManager.serverDisplayName(account);
        if (server != null && !server.isBlank()) {
            return Component.literal(server);
        }
        return TRANSLATOR.translate(account.getAccountType());
    }
}
