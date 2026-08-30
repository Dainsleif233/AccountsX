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
        return handle.translate(
                "accountsx.account.type." + account.getAccountType().name().toLowerCase(Locale.ROOT) + ".using",
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
