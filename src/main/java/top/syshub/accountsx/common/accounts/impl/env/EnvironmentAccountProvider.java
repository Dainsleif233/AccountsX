package top.syshub.accountsx.common.accounts.impl.env;

import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;

public final class EnvironmentAccountProvider implements AccountProvider<EnvironmentAccount> {
    @Override
    public AccountContext createAccountContext(EnvironmentAccount account) {
        return null;
    }

    @Override
    public void refresh(EnvironmentAccount account) {
    }
}
