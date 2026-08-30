package top.syshub.accountsx.common.accounts;

import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.ui.Memory;
import top.syshub.accountsx.common.ui.UIScreen;

import java.io.IOException;

public interface AccountProvider<T extends BaseAccount> {
    int STATE_IMMEDIATE_CLOSE = 0;

    int STATE_HANDLE = 1;

    void configure(UIScreen screen);

    int validate(UIScreen screen, Memory memory) throws IllegalArgumentException;

    AccountContext createAccountContext(T account) throws IOException;

    T login(Memory memory) throws IOException;

    void refresh(T account) throws IOException;

    @SuppressWarnings("unchecked")
    static <T extends BaseAccount> AccountProvider<T> getProvider(T account) {
        return (AccountProvider<T>) account.getAccountType().getAccountProvider();
    }
}
