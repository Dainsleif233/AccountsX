package top.syshub.accountsx.common.ui;

import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.BaseAccount;

import java.util.Locale;

public final class Translator<R> {
    private static final String[] EMPTY_ARGS = new String[0];

    private final Handle<R> handle;

    public Translator(Handle<R> handle) {
        this.handle = handle;
    }

    public R translate(AccountType type) {
        return handle.translate("accountsx.account.type." + type.name().toLowerCase(Locale.ROOT) + ".name");
    }

    public R translate(AccountState state) {
        return handle.translate("accountsx.account.state." + state.name().toLowerCase(Locale.ROOT) + ".name");
    }

    public R translate(BaseAccount account) {
        return this.translate(account, null);
    }

    /**
     * 外置账号的来源是它所在的认证服务器：类型名对所有外置账号都一样，没有信息量。
     * {@code sourceName} 为 null 或空白时（服务器条目丢了、还没探测到）退回类型名。
     */
    public R translate(BaseAccount account, String sourceName) {
        String type = account.getAccountType().name().toLowerCase(Locale.ROOT);
        if (sourceName != null && !sourceName.isBlank()) {
            return handle.translate(
                    "accountsx.account.type." + type + ".using_server",
                    sourceName,
                    account.getAccountStorage().getPlayerName()
            );
        }
        return handle.translate(
                "accountsx.account.type." + type + ".using",
                account.getAccountStorage().getPlayerName()
        );
    }

    public interface Handle<R> {
        R translate(String key, String... args);

        default R translate(String key) {
            return translate(key, EMPTY_ARGS);
        }
    }
}
