package top.syshub.accountsx.core.adapters.api;

import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.model.context.AccountContext;

import java.io.IOException;
import java.net.Proxy;

public interface AuthlibAdapter<S extends AccountSession> {
    S createAccountProfile(BaseAccount.AccountStorage storage, AccountContext context, Proxy proxy) throws IOException;
}
