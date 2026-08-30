package top.syshub.accountsx.common.adapters.api;

import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;

import java.io.IOException;
import java.net.Proxy;

public interface AuthlibBridge<S extends AccountSession> {
    S createAccountProfile(BaseAccount.AccountStorage storage, AccountContext context, Proxy proxy) throws IOException;
}
