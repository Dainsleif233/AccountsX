package top.syshub.accountsx.core.accounts;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.internal.Streams;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import top.syshub.accountsx.core.accounts.model.AccountState;
import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.utils.Threading;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@JsonAdapter(BaseAccount.Adapter.class)
public abstract class BaseAccount {
    static final class Adapter implements TypeAdapterFactory {
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
            if (typeToken.getRawType() != BaseAccount.class) {
                return null;
            }

            Map<AccountType, TypeAdapter<BaseAccount>> cache = new ConcurrentHashMap<>();

            return (TypeAdapter<T>) new TypeAdapter<BaseAccount>() {
                private TypeAdapter<BaseAccount> compute(AccountType type) {
                    return cache.computeIfAbsent(type, t -> (TypeAdapter<BaseAccount>) gson.getDelegateAdapter(
                            Adapter.this, TypeToken.get(t.getAccountClass())
                    ));
                }

                @Override
                public void write(JsonWriter out, BaseAccount account) throws IOException {
                    Streams.write(compute(account.getAccountType()).toJsonTree(account).getAsJsonObject(), out);
                }

                @Override
                public BaseAccount read(JsonReader in) {
                    JsonObject jo = Streams.parse(in).getAsJsonObject();
                    return compute(gson.fromJson(jo.get("type"), AccountType.class)).fromJsonTree(jo);
                }
            };
        }
    }

    public static final class AccountStorage {
        private final String accessToken;

        private final String playerName;

        private final UUID playerUUID;

        private final transient AccountState state;

        private AccountStorage() {
            this.accessToken = null;
            this.playerName = null;
            this.playerUUID = null;
            this.state = AccountState.UNAUTHORIZED;
        }

        private AccountStorage(String accessToken, String playerName, UUID playerUUID, AccountState state) {
            this.accessToken = accessToken;
            this.playerName = playerName;
            this.playerUUID = playerUUID;
            this.state = state;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public String getPlayerName() {
            return playerName;
        }

        public UUID getPlayerUUID() {
            return playerUUID;
        }

        public AccountState getState() {
            return state;
        }
    }

    protected volatile AccountStorage storage;

    private final AccountType type;

    private final String accountName;

    // 1.11 修复：worker 线程 setAvatar、客户端线程渲染读取，需 volatile 保证可见性。
    // P1.4：头像 base64 不再进配置；只保留内容哈希 key 与缓存时间戳，PNG 落盘
    // ~/.cache/accountsx/avatars/<avatarKey>.png（见 top.syshub.accountsx.image.AvatarCache）。
    private volatile String avatarKey;

    private volatile long avatarCachedAt;

    protected BaseAccount(String accessToken, String playerName, UUID playerUUID, AccountType type, String accountName, String avatarKey, long avatarCachedAt) {
        this.storage = new AccountStorage(accessToken, playerName, playerUUID, AccountState.AUTHORIZED);
        this.type = type;
        this.accountName = accountName;
        this.avatarKey = avatarKey;
        this.avatarCachedAt = avatarCachedAt;
    }

    public AccountStorage getAccountStorage() {
        return storage;
    }

    public final AccountType getAccountType() {
        return type;
    }

    public String getAccountName() {
        return this.accountName;
    }

    /**
     * 头像 PNG 的内容哈希 key（SHA-256 hex），用于从
     * {@code ~/.cache/accountsx/avatars/<avatarKey>.png} 读取头像。null 表示尚无头像。
     */
    public String getAvatarKey() {
        return this.avatarKey;
    }

    /**
     * 头像缓存写入时间戳（{@code System.currentTimeMillis()}），0 表示未缓存。
     */
    public long getAvatarCachedAt() {
        return this.avatarCachedAt;
    }

    public void setAvatar(String avatarKey, long avatarCachedAt) {
        this.avatarKey = avatarKey;
        this.avatarCachedAt = avatarCachedAt;
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setProfile(String accessToken, String playerName, UUID playerUUID) {
        Threading.checkAccountWorkerThread();

        this.storage = new AccountStorage(accessToken, playerName, playerUUID, AccountState.AUTHORIZED);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setProfileState(AccountState state) {
        Threading.checkAccountWorkerThread();

        AccountStorage s = this.storage;
        this.storage = new AccountStorage(s.accessToken, s.playerName, s.playerUUID, state);
    }
}
