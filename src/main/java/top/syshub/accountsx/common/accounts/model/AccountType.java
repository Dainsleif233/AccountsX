package top.syshub.accountsx.common.accounts.model;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount;
import top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.injector.impl.UnitedInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftAccount;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftAccountProvider;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccountProvider;
import top.syshub.accountsx.common.adapters.Platforms;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@JsonAdapter(AccountType.AccountTypeAdapter.class)
public enum AccountType {
    ENV_DEFAULT(EnvironmentAccount.class, new EnvironmentAccountProvider(Platforms.getHttpGateway()), null),
    OFFLINE(OfflineAccount.class, new OfflineAccountProvider(Platforms.getHttpGateway()), "offline"),
    MICROSOFT(MicrosoftAccount.class, new MicrosoftAccountProvider(Platforms.getHttpGateway()), "microsoft"),
    AUTHLIB_INJECTOR(AuthlibInjectorAccountProvider.AuthlibInjectorAccount.class, new AuthlibInjectorAccountProvider(Platforms.getHttpGateway()), "injector.authlib-injector"),
    UNITED_INJECTOR(UnitedInjectorAccountProvider.UnitedInjectorAccount.class, new UnitedInjectorAccountProvider(Platforms.getHttpGateway()), "injector.united");

    public static final AccountType[] VALUES = values();

    public static final AccountType[] CONFIGURABLE_VALUES = Arrays.stream(VALUES).filter(i -> i != ENV_DEFAULT).toArray(AccountType[]::new);

    private final Class<? extends BaseAccount> accountClass;

    private final AccountProvider<?> accountProvider;

    private final String id;

    AccountType(Class<? extends BaseAccount> accountClass, AccountProvider<?> accountProvider, String id) {
        this.accountClass = accountClass;
        this.accountProvider = accountProvider;
        this.id = id;
    }

    public Class<? extends BaseAccount> getAccountClass() {
        return accountClass;
    }

    public AccountProvider<?> getAccountProvider() {
        return accountProvider;
    }

    public static class AccountTypeAdapter extends TypeAdapter<AccountType> {
        private static final Map<String, AccountType> LOOKUP = Arrays.stream(AccountType.CONFIGURABLE_VALUES).collect(Collectors.toUnmodifiableMap(
                type -> type.id, Function.identity()
        ));

        @Override
        public void write(JsonWriter out, AccountType value) throws IOException {
            out.value(value.id);
        }

        @Override
        public AccountType read(JsonReader in) throws IOException {
            String name = in.nextString();
            AccountType type = LOOKUP.get(name);
            if (type != null) {
                return type;
            }

            throw new IOException("Unknown account type: " + name);
        }
    }
}