package top.syshub.accountsx.core.accounts.impl.env;

import top.syshub.accountsx.core.accounts.AccountProvider;
import top.syshub.accountsx.core.accounts.model.context.AccountContext;
import top.syshub.accountsx.core.net.HttpGateway;
import top.syshub.accountsx.core.ui.Memory;
import top.syshub.accountsx.core.ui.UIScreen;

import java.util.Objects;

public final class EnvironmentAccountProvider implements AccountProvider<EnvironmentAccount> {
    // 环境账号不发起网络请求；构造器接收网关仅为统一所有 provider 的构造形态（P1.3）。
    public EnvironmentAccountProvider(HttpGateway http) {
        Objects.requireNonNull(http, "http gateway");
    }
    @Override
    public AccountContext createAccountContext(EnvironmentAccount account) {
        return null;
    }

    @Override
    public void configure(UIScreen screen) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int validate(UIScreen screen, Memory memory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public EnvironmentAccount login(Memory memory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void refresh(EnvironmentAccount account) {
    }
}
