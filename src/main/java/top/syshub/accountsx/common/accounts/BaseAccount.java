package top.syshub.accountsx.common.accounts;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.internal.Streams;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.utils.Threading;

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

        /**
         * 当前 accessToken 的过期时刻（epoch 毫秒）；0 表示未知。
         *
         * <p>Yggdrasil 规范里 {@code /authserver/refresh} 不保证返回 {@code expires_in}，所以外置账号
         * 多数时候是 0：它们靠 {@code /authserver/validate} 判断令牌是否还能用（不联网轮换）。
         * 微软链路一定会返回 {@code expires_in}，据此可以跳过整条 Xbox → Minecraft 换票。</p>
         */
        private final long expiresAt;

        // Gson 通过反射（getDeclaredConstructor）调用此无参构造器反序列化 storage。
        // 同时它是 transient 的 state 唯一赋默认值之处：UNAUTHORIZED（不进 JSON，反序列化后保持）。
        // 若删除，Gson 回退到 Unsafe.allocateInstance，state 会变为 null 而非 UNAUTHORIZED，
        // 导致账号列表渲染 getState() 时为空/异常。IDE 报“从未使用”属静态分析误报，故抑制。
        @SuppressWarnings("unused")
        private AccountStorage() {
            this.accessToken = null;
            this.playerName = null;
            this.playerUUID = null;
            this.expiresAt = 0L;
            this.state = AccountState.UNAUTHORIZED;
        }

        private AccountStorage(String accessToken, String playerName, UUID playerUUID, AccountState state) {
            this(accessToken, playerName, playerUUID, 0L, state);
        }

        private AccountStorage(String accessToken, String playerName, UUID playerUUID, long expiresAt, AccountState state) {
            this.accessToken = accessToken;
            this.playerName = playerName;
            this.playerUUID = playerUUID;
            this.expiresAt = expiresAt;
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

        public long getExpiresAt() {
            return expiresAt;
        }
    }

    protected volatile AccountStorage storage;

    private final AccountType type;

    private final String accountName;

    // 1.11 修复：worker 线程 setAvatar、客户端线程渲染读取，需 volatile 保证可见性。
    // P1.4：头像 base64 不再进配置；只保留内容哈希 key 与缓存时间戳，PNG 落盘
    // ~/.cache/accountsx/avatars/<avatarKey>.png（见 top.syshub.accountsx.common.utils.image.AvatarCache）。
    private volatile String avatarKey;

    // 头像缓存时间戳（System.currentTimeMillis()，0 表示未缓存）。
    // 经 Gson 反射读写完成持久化，并为后续缓存失效逻辑预留；Java 层当前无读取方属正常，
    // 故标 @SuppressWarnings("unused") 抑制“已分配但从未访问”误报（非真死代码）。
    @SuppressWarnings("unused")
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
     * 写入头像内容哈希 key 与缓存时间戳。
     * 二者均经 Gson 持久化；缓存时间戳当前无 Java 层读取方（留给后续缓存失效逻辑），故不暴露 getter。
     */
    public void setAvatar(String avatarKey, long avatarCachedAt) {
        this.avatarKey = avatarKey;
        this.avatarCachedAt = avatarCachedAt;
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setProfile(String accessToken, String playerName, UUID playerUUID) {
        Threading.checkAccountWorkerThread();

        this.storage = new AccountStorage(accessToken, playerName, playerUUID, 0L, AccountState.AUTHORIZED);
    }

    /** 同 {@link #setProfile(String, String, UUID)}，并记录服务端给出的令牌过期时刻（epoch 毫秒）。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setProfile(String accessToken, String playerName, UUID playerUUID, long expiresAt) {
        Threading.checkAccountWorkerThread();

        this.storage = new AccountStorage(accessToken, playerName, playerUUID, expiresAt, AccountState.AUTHORIZED);
    }

    /**
     * 记录当前 accessToken 的过期时刻（epoch 毫秒，0 表示未知）。
     *
     * <p>只由 provider 在拿到 {@code expires_in} 后调用；下一次启动据此判断能否直接复用令牌，
     * 不必为了确认有效期把令牌轮换一遍。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setTokenExpiry(long expiresAt) {
        Threading.checkAccountWorkerThread();
        setTokenExpiryForLoad(expiresAt);
    }

    /** 与 {@link #setTokenExpiry} 同效果但无线程检查：仅 {@code AccountRepository.fromJson} 还原持久化值。 */
    public final void setTokenExpiryForLoad(long expiresAt) {
        AccountStorage s = this.storage;
        this.storage = new AccountStorage(s.accessToken, s.playerName, s.playerUUID, expiresAt, s.state);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public final void setProfileState(AccountState state) {
        Threading.checkAccountWorkerThread();

        AccountStorage s = this.storage;
        this.storage = new AccountStorage(s.accessToken, s.playerName, s.playerUUID, s.expiresAt, state);
    }

    /**
     * 采纳另一个实例已经换好的令牌（多实例协调专用）。
     *
     * <p>同一账号不可能被两个进程各持一份有效令牌：服务端每次换票都会作废旧值。所以第二个
     * 实例在用令牌之前先读盘，磁盘上的条目比自己新就直接采纳，绝不能自己去换票——那会把
     * 对方刚拿到的令牌顶掉，表现为"另一客户端莫名其妙掉线"。</p>
     *
     * <p>不加线程检查：调用点位于仓储对账（串行化在 synchronized 内），并非 provider 的刷新路径。</p>
     */
    public final void adoptFromDisk(String accessToken, String playerName, UUID playerUUID, long expiresAt) {
        this.storage = new AccountStorage(accessToken, playerName, playerUUID, expiresAt, AccountState.AUTHORIZED);
    }
}
