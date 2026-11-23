package top.syshub.accountsx.common.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount;
import top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;

import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.server.AuthServerKind;
import top.syshub.accountsx.common.auth.server.AuthServerRegistry;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilDeviceCodeFlow;
import top.syshub.accountsx.common.storage.ConfigRoot;
import top.syshub.accountsx.common.storage.ConfigStore;
import top.syshub.accountsx.common.sync.AccountKey;
import top.syshub.accountsx.common.sync.CoordinationService;
import top.syshub.accountsx.common.task.TaskScheduler;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 账号仓储（P2.4）：内存账号集合 + 当前账号指针 + 持久化编排的新家。
 *
 * <p>旧 {@code AccountManager} 只剩编排（刷新/登录），集合操作（add / drop / move）与
 * "当前账号"状态全搬到这里。P3 会再给出 typed 的账号/服务器模型；本阶段仓储只做
 * JsonObject（{@link ConfigRoot} 的 live 数组）与 {@link BaseAccount} 之间的双向映射，
 * 不提前绑定模型。</p>
 *
 * <p>普通变更经 {@link #markDirty()} 合并保存，换票结果在释放账号租约前同步保存。</p>
 */
public final class AccountRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountRepository.class);

    private final ConfigStore store;
    private volatile ConfigRoot root;
    private final List<BaseAccount> accounts = new ArrayList<>();
    private volatile BaseAccount current;

    /** 已排队的合并写任务。 */
    private volatile boolean writePending;

    /** 服务器注册表（P3.1）：servers 条目的内存视图 + 元数据缓存；变更经 markDirty 落盘。 */
    private final AuthServerRegistry authServers = new AuthServerRegistry(Platforms.getHttpGateway(), this::markDirty);

    /**
     * 多实例协调（P6）；{@code null} 表示单实例模式（单元测试与无配置文件场景）。
     *
     * <p>一旦装上，写路径变成"锁内读盘 → 合并 → 写回"，读路径多一个"用令牌前先对账"。</p>
     */
    private volatile CoordinationService sync;

    /** 普通对账和保存跳过这些账号的凭据，直到换票结束。 */
    private final Set<String> refreshingKeys = new LinkedHashSet<>();

    /**
     * 保存路径在锁内对账得到的结论（目前只关心"当前账号被别处删掉了"）。
     *
     * <p>写路径先对账再合并写，所以它比轮询更早看到中间态；如果只有轮询负责回落，
     * 这次删除就可能被写成"别人的旧快照"，游戏会话却一直留在已删除的账号上。</p>
     */
    private volatile java.util.function.Consumer<ReconcileResult> reconcileListener;

    /** 每个账号最后一次落盘/采纳的 revision（缺省 0）。用来判断磁盘上的条目是不是比内存里的新。 */
    private final Map<String, Long> revisions = new HashMap<>();

    /** 本地已删除但还没写进文件的墓碑（key → 删除时刻），写入时并进 {@code removed} 数组。 */
    private final Map<String, Long> pendingRemovals = new LinkedHashMap<>();

    /**
     * 已删除账号见过的最高 revision（key → rev）。
     *
     * <p>rev 只在「同一个逻辑账号」（同类型 + 同 UUID + 同服务器）内比较。删掉再加回同一个账号时
     * 磁盘上已经没有它的条目，若 rev 从 1 重新开始，另一个实例手里那份旧快照（rev=N）被采纳时就会
     * 盖掉刚登录的新凭据。这里记住删除前的 rev，也并进磁盘墓碑，重加时从它继续往上戳。</p>
     */
    private final Map<String, Long> removedRevs = new HashMap<>();

    /**
     * 本地拖动排名的版本号。
     *
     * <p>列表顺序也是共享状态：对端拖动过之后，本地一次无关的普通保存若按内存里的旧顺序写回，
     * 就会把对端的调整抹掉。这里只在用户真的拖过（{@link #move}）时递增，普通增删不改顺序；
     * 版本号与已落盘的对齐之前，对账都按磁盘顺序重排。</p>
     */
    private long orderVersion;

    /** 已落盘的顺序版本；小于 {@link #orderVersion} 表示本地还有没写出去的排名。 */
    private long persistedOrderVersion;

    /**
     * 本实例刚重新添加的账号 key。
     *
     * <p>墓碑是给"别的实例手里那份旧快照"用的：它不能让删掉的账号复活，但也不能永久封死这个
     * 账号——用户删掉再重新添加同一个账号（同类型同 UUID 同服务器）是完全正常的操作。
     * 只有本实例<b>显式添加</b>过的 key 才压在墓碑之上；另一实例内存里的旧副本仍然会被墓碑挡住。</p>
     */
    private final Map<String, BaseAccount> revivedAccounts = new HashMap<>();

    /** {@code removed} 数组最多保留的墓碑数：只用于防止"复活"，留太多没必要。 */
    private static final int MAX_TOMBSTONES = 200;

    public AccountRepository(ConfigStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 装载全局配置或导入旧实例配置，随后把启动器账号放在列表首位。
     */
    public synchronized void initialize(BaseAccount environment, Path legacyGameConfigDir) {
        accounts.clear();
        if (environment != null)
            accounts.add(environment);
        current = environment;

        root = store.load(legacyGameConfigDir);
        for (JsonElement element : root.accounts()) {
            if (!(element instanceof JsonObject jo)) {
                LOGGER.warn("全局配置含非对象账号条目，已跳过。");
                continue;
            }
            try {
                BaseAccount account = fromJson(jo, root.servers());
                if (account != null)
                    accounts.add(account);
                // 未知 type 返回 null：下面 rebuild Preserved 会原样带回，不删除。
            } catch (RuntimeException e) {
                // 单条坏了只跳过该条：未知 type 在 1→2 已移除，这里只防手工改坏的字段。
                LOGGER.warn("全局配置含无法解析的账号条目，已跳过。", e);
            }
        }

        revisions.clear();
        for (JsonElement element : root.accounts()) {
            if (element instanceof JsonObject jo) {
                String key = AccountKey.of(jo);
                if (key != null)
                    revisions.put(key, longValue(jo.get("rev")));
            }
        }
        // 磁盘墓碑里的 rev 是"这个账号曾经到过的最大的 rev"，重加它时必须超过这个值。
        removedRevs.clear();
        removedRevs.putAll(tombstoneRevs(root.raw()));

        // 注册表接管 servers（P3.1）：装载 + 补齐固定条目 + 安装到 provider 可见的单例槽。
        // 被墓碑标记的条目跳过：删掉的服务器不能在重启后从自己留下的墓碑旁边复活。
        authServers.load(root.servers(), serverTombstones(root.raw()));
        AbstractInjectorAccountProvider.installRegistry(authServers);
    }

    /** P3.1：服务器注册表（登录/刷新经注册表使用）。 */
    public AuthServerRegistry authServers() {
        return authServers;
    }

    /**
     * 装上多实例协调，写入时使用同一配置锁。
     */
    public void attachSync(CoordinationService coordination) {
        this.sync = coordination;
    }

    /** 当前协调器；{@code null} 表示单实例模式。 */
    public CoordinationService sync() {
        return sync;
    }

    /** 订阅保存路径里的对账结论。回调在写线程上同步发生，实现方自己切到界面线程。 */
    public void onReconciled(java.util.function.Consumer<ReconcileResult> listener) {
        this.reconcileListener = listener;
    }

    /**
     * 启动预热（P3.1）：在串行通道上逐 server 探测元数据（公钥 / skinDomains / OIDC 端点），
     * 失败保留旧条目（stale）、不阻塞。之后登录/刷新只读内存。
     */
    public void warmAuthServers() {
        TaskScheduler.submit(() -> authServers.refreshAll());
    }

    private static JsonObject parseObject(String text) {
        try {
            // 裸 JsonParser：NetworkUtils.GSON 带 UUIDTypeAdapter，读 JsonElement 目标会炸（P2.4 实测）。
            JsonElement parsed = com.google.gson.JsonParser.parseString(text);
            return parsed instanceof JsonObject jo ? jo : null;
        } catch (RuntimeException e) {
            LOGGER.warn("JSON 解析失败。", e);
            return null;
        }
    }

    // ── 集合操作（CLIENT 线程，沿用 AccountManager 的线程契约） ──

    public synchronized List<BaseAccount> view() {
        return List.copyOf(accounts);
    }

    public BaseAccount getCurrent() {
        return current;
    }

    public synchronized void setCurrent(BaseAccount account) {
        current = account;
    }

    public synchronized void add(BaseAccount account) {
        accounts.add(Objects.requireNonNull(account, "account"));
        // 删掉再重新添加同一个账号：本地意图优先于墓碑，否则这个账号会永远写不进去
        // （写入时被墓碑跳过，重启后凭空消失）。
        String key = AccountKey.of(account);
        if (key != null) {
            pendingRemovals.remove(key);
            revivedAccounts.put(key, account);
        }
        markDirty();
    }

    public synchronized void drop(BaseAccount account) {
        if (account == null || !accounts.remove(account))
            return;
        // 墓碑：删掉的账号可能还躺在另一个实例的内存里，它下一次保存必须知道"这条已经删了"，
        // 否则合并写会把它复活。
        String key = AccountKey.of(account);
        if (key != null) {
            // 记住删除时的 rev：重加同一个账号时必须从它往上戳，否则对端旧快照的 rev=N 会盖掉新凭据。
            removedRevs.merge(key, revisions.getOrDefault(key, 0L), Math::max);
            pendingRemovals.put(key, System.currentTimeMillis());
            revisions.remove(key);
            revivedAccounts.remove(key);
        }
        // 删掉的正是当前账号：指针立刻落回默认账号（启动器会话），别留着指向已删除的对象。
        // 真正的游戏会话切换由 AccountManager 在 worker 上做——仓储只管状态。
        if (current == account)
            current = defaultAccount();
        markDirty();
    }

    /** 文件不存在时允许重建；读取失败时保留磁盘原件并放弃本次写入。 */
    private JsonObject readDiskRaw() throws IOException {
        CoordinationService coordination = this.sync;
        if (coordination == null)
            return null;
        String text;
        try {
            text = Files.readString(coordination.paths().configFile(), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(text);
            if (parsed instanceof JsonObject disk && disk.get("version") instanceof JsonPrimitive version
                    && version.isNumber() && version.getAsBigDecimal().compareTo(BigDecimal.valueOf(ConfigStore.CURRENT_VERSION)) == 0
                    && disk.get("accounts") instanceof JsonArray && disk.get("servers") instanceof JsonArray)
                return disk;
        } catch (RuntimeException e) {
            // 解析异常可能包含配置片段，不透出原文。
        }
        throw new IOException("全局配置格式异常或版本不匹配，已保留文件。");
    }

    /** 当前内存里持有该 key 的账号（对账用）；没有则 {@code null}。 */
    public synchronized BaseAccount findByKey(String key) {
        if (key == null)
            return null;
        for (BaseAccount account : accounts) {
            if (key.equals(AccountKey.of(account)))
                return account;
        }
        return null;
    }

    /**
     * 与磁盘对账：采纳别处换好的令牌、收下别处新增的账号、丢弃别处删掉的账号。
     *
     * @return 变化描述；没有变化时 {@link ReconcileResult#NONE}
     */
    public synchronized ReconcileResult reconcile() {
        try {
            JsonObject disk = readDiskRaw();
            return disk == null ? ReconcileResult.NONE : reconcileWith(disk, null);
        } catch (IOException e) {
            LOGGER.warn("读取全局配置失败，本次对账已跳过。", e);
            return ReconcileResult.NONE;
        }
    }

    /** 在持有账号租约时读取磁盘。已知读失败时不换票，以免作废对方的新令牌。 */
    synchronized boolean prepareRefresh(BaseAccount account, String key) throws IOException {
        if (!accounts.contains(account))
            return false;
        JsonObject disk = readDiskRaw();
        if (disk != null) {
            if (tombstoneKeys(disk).contains(key) && !revivedAccounts.containsKey(key))
                return false;
            reconcileWith(disk, key);
        }
        return true;
    }

    synchronized boolean beginRotation(BaseAccount account, String key) {
        return accounts.contains(account) && refreshingKeys.add(key);
    }

    synchronized void endRotation(String key) {
        refreshingKeys.remove(key);
    }

    private synchronized ReconcileResult reconcileWith(JsonObject disk, String onlyKey) {
        boolean changed = false;
        BaseAccount removedCurrent = null;
        BaseAccount recredentialedCurrent = null;

        Map<String, JsonObject> diskByKey = new LinkedHashMap<>();
        Set<String> tombstoned = tombstoneKeys(disk);
        tombstoneRevs(disk).forEach((key, rev) -> removedRevs.merge(key, rev, Math::max));
        for (JsonElement element : arrayOf(disk, "accounts")) {
            if (!(element instanceof JsonObject jo))
                continue;
            String key = AccountKey.of(jo);
            if (key == null || tombstoned.contains(key))
                continue;
            diskByKey.put(key, jo);
        }

        // 1) 本地账号：磁盘更新则采纳；磁盘没了则是别处删掉了（ENV 永不落盘，跳过）
        for (BaseAccount account : List.copyOf(accounts)) {
            if (account.getAccountType() == AccountType.ENV_DEFAULT)
                continue;
            String key = AccountKey.of(account);
            if (key == null || (onlyKey != null && !onlyKey.equals(key)))
                continue;
            JsonObject entry = diskByKey.get(key);
            if (entry == null) {
                if (onlyKey != null)
                    continue;
                // 刚重新添加、还没落盘的账号：磁盘上暂时没有，不能当成"别处删掉了"。
                if (revivedAccounts.containsKey(key))
                    continue;
                // 只在墓碑存在时才算"被删除"：磁盘读不懂/被清空时不能把账号全丢了
                if (!tombstoned.contains(key))
                    continue;
                accounts.remove(account);
                removedRevs.merge(key, revisions.getOrDefault(key, 0L), Math::max);
                revisions.remove(key);
                if (current == account) {
                    current = defaultAccount();
                    removedCurrent = account;
                }
                changed = true;
                continue;
            }
            if (refreshingKeys.contains(key) || revivedAccounts.containsKey(key))
                continue;
            long diskRev = longValue(entry.get("rev"));
            if (diskRev > revisions.getOrDefault(key, 0L)) {
                boolean adopted = adopt(account, entry, arrayOf(disk, "servers"));
                // 当前账号的令牌被对端换掉了：游戏里那条 User 还是旧的，等调用方决定怎么补。
                if (adopted && current == account)
                    recredentialedCurrent = account;
                changed |= adopted;
                revisions.put(key, diskRev);
            }
        }

        // 2) 磁盘上别的实例新增的账号：收进内存，界面才能看见
        if (onlyKey == null) {
            // 顺序也属于共享状态：本地这次没有改过顺序时，按磁盘顺序重排，
            // 否则下一次普通保存会把对端拖动过的排名写回旧顺序。
            if (orderVersion == persistedOrderVersion)
                changed |= adoptDiskOrder(diskByKey);
            Set<String> local = new LinkedHashSet<>();
            for (BaseAccount account : accounts) {
                String key = AccountKey.of(account);
                if (key != null)
                    local.add(key);
            }
            for (Map.Entry<String, JsonObject> entry : diskByKey.entrySet()) {
                if (local.contains(entry.getKey()))
                    continue;
                // 本地刚删掉、还没落盘的账号：磁盘上还在，但用户已经不要它了，别收回来。
                if (pendingRemovals.containsKey(entry.getKey()))
                    continue;
                try {
                    BaseAccount account = fromJson(entry.getValue(), arrayOf(disk, "servers"));
                    if (account != null) {
                        accounts.add(account);
                        revisions.put(entry.getKey(), longValue(entry.getValue().get("rev")));
                        changed = true;
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("对账时跳过无法解析的账号条目。", e);
                }
            }
            // 3) 认证服务器：双向合并，缺一个方向就会丢数据
            //    - 对端新增的条目：本地保存会把它当成"多余"抹掉，必须收进来；
            //    - 对端删除的条目（驱动墓碑）：本地内存里那份旧副本不能写回去；
            //    - 对端重新探测过的同键条目：本地旧端点会在下一次普通保存时盖回去。
            // 本地刚删掉、还没落盘的条目也算墓碑：否则这次写入会从磁盘把它当"对端新增"收回来。
            Set<String> serverTombstoned = new LinkedHashSet<>(serverTombstones(disk));
            serverTombstoned.addAll(authServers.localTombstones().removed().keySet());
            boolean serversChanged = authServers.dropTombstoned(serverTombstoned);
            AuthServerRegistry.Merge mergedServers =
                    authServers.mergeFromDisk(arrayOf(disk, "servers"), serverTombstoned);
            if (mergedServers.needsWarm())
                warmAuthServers();
            changed |= serversChanged || mergedServers.changed();
        }

        return new ReconcileResult(changed, removedCurrent, recredentialedCurrent);
    }

    /**
     * 把内存账号顺序调整为磁盘顺序（本地没有未落盘的顺序变更时才调用）。
     *
     * <p>环境账号永远第一且不落盘；磁盘上暂时没有的账号（刚添加、本地刚删除）保持在末尾的
     * 原有相对顺序，等它们自己写进文件后自然会归位。</p>
     */
    private boolean adoptDiskOrder(Map<String, JsonObject> diskByKey) {
        List<BaseAccount> reordered = new ArrayList<>(accounts.size());
        Map<String, BaseAccount> pending = new LinkedHashMap<>();
        BaseAccount environment = null;
        for (BaseAccount account : accounts) {
            if (account.getAccountType() == AccountType.ENV_DEFAULT) {
                environment = account;
                continue;
            }
            String key = AccountKey.of(account);
            if (key == null)
                // 没有稳定身份的账号无法参加排序，按出现顺序放末尾
                pending.put("no-key:" + pending.size(), account);
            else
                pending.put(key, account);
        }
        if (environment != null)
            reordered.add(environment);
        for (String key : diskByKey.keySet()) {
            BaseAccount account = pending.remove(key);
            if (account != null)
                reordered.add(account);
        }
        reordered.addAll(pending.values());
        if (reordered.equals(accounts))
            return false;
        accounts.clear();
        accounts.addAll(reordered);
        return true;
    }

    private boolean adopt(BaseAccount account, JsonObject entry, JsonArray servers) {
        JsonObject profile = entry.get("profile") instanceof JsonObject p ? p : null;
        JsonObject credentials = entry.get("credentials") instanceof JsonObject c ? c : new JsonObject();
        String accessToken = str(credentials.get("accessToken"));
        long expiresAt = longValue(credentials.get("expiresAt"));
        BaseAccount.AccountStorage storage = account.getAccountStorage();
        boolean changed = !Objects.equals(accessToken, storage.getAccessToken()) || expiresAt != storage.getExpiresAt();
        String refreshToken = str(credentials.get("refreshToken"));
        if (account instanceof MicrosoftAccount ms && !Objects.equals(refreshToken, ms.getMicrosoftAccountRefreshToken())) {
            ms.setMicrosoftAccountToken(null, refreshToken);
            changed = true;
        }
        if (account instanceof AbstractInjectorAccount injector) {
            JsonObject envelope = oauthEnvelope(credentials, injector.getServer(), servers);
            String loginToken = envelope == null ? accessToken : "OAuth " + envelope;
            String clientToken = str(credentials.get("clientToken"));
            if (!Objects.equals(loginToken, injector.getLoginToken())
                    || !Objects.equals(clientToken, injector.getClientToken())) {
                injector.setLoginTokenForMigration(loginToken, injector.getPreferredPlayerUUID());
                injector.setClientTokenForMigration(clientToken);
                changed = true;
            }
        }
        if (changed) {
            UUID uuid = profile == null ? null : parseUuid(str(profile.get("uuid")));
            String name = profile == null ? null : str(profile.get("name"));
            account.adoptFromDisk(accessToken, name != null ? name : storage.getPlayerName(),
                    uuid != null ? uuid : storage.getPlayerUUID(), expiresAt);
        }
        return changed;
    }

    /**
     * 我们从磁盘那份文件里读到的服务器墓碑（{@link AuthServer#tombstoneKey} 形式）。
     *
     * <p>只取本次读到的：内存里的 {@code removedRevs} 可能还留着更早的条目，而这里要回答的是
     * "对面这份快照认为哪些服务器已经被删了"。</p>
     */
    private static Set<String> serverTombstones(JsonObject disk) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : arrayOf(disk, "removed")) {
            if (element instanceof JsonObject jo) {
                String key = str(jo.get("key"));
                if (AuthServer.isServerTombstone(key))
                    result.add(key);
            }
        }
        return result;
    }

    /**
     * 对账结果。
     *
     * @param changed               内存里的账号/服务器是否变了（界面需要刷新）
     * @param removedCurrent        当前账号被别处删掉（需要切回默认账号）
     * @param recredentialedCurrent 当前账号的凭据被别处换掉（游戏里那条会话还拿着旧令牌）
     */
    public record ReconcileResult(boolean changed, BaseAccount removedCurrent, BaseAccount recredentialedCurrent) {
        public static final ReconcileResult NONE = new ReconcileResult(false, null, null);
    }

    /**
     * 属于某个认证服务器的账号快照。
     *
     * <p>{@link #dropAccountsOn} 之后这些对象就不在列表里了，但撤销服务端令牌时仍然需要它们，
     * 所以删除前先取一份。</p>
     */
    public synchronized List<BaseAccount> accountsOn(String apiRoot) {
        List<BaseAccount> result = new ArrayList<>();
        for (BaseAccount account : accounts) {
            if (isOnServer(account, apiRoot))
                result.add(account);
        }
        return List.copyOf(result);
    }

    /**
     * 删除所有属于 {@code apiRoot} 的账号（认证服务器条目被移除时连带清理）。
     *
     * <p>条目一走，它的登录端点、公钥和 skinDomains 就都读不到了：这些账号既登录不了也刷新不了，
     * 留在列表里只会占位并在下次刷新时报错。</p>
     *
     * @return 被删的账号里是否包含当前账号（true 表示调用方需要把游戏会话切回默认账号）
     */
    public synchronized boolean dropAccountsOn(String apiRoot) {
        boolean currentDropped = false;
        for (BaseAccount account : List.copyOf(accounts)) {
            if (!isOnServer(account, apiRoot))
                continue;
            if (current == account)
                currentDropped = true;
            drop(account);
        }
        return currentDropped;
    }

    /**
     * 账号是否属于某个外置认证服务器：按账号自己保存的 apiRoot 判定。
     * UI 侧筛选与「删服务器连带删账号」共用这一条规则，避免两处各写一遍。
     */
    public static boolean isOnServer(BaseAccount account, String apiRoot) {
        return account != null
                && apiRoot != null
                && account.getAccountType() == AccountType.AUTHLIB_INJECTOR
                && account instanceof AbstractInjectorAccount injector
                && apiRoot.equals(injector.getServer());
    }

    /**
     * 默认账号：启动器会话（{@link AccountType#ENV_DEFAULT}）。它永远排在列表首位且不可删除/移动，
     * 所以「当前账号被删掉」时回落到它就是回到「刚开游戏」的状态。没有环境账号（例如启动器
     * 没给会话）时返回 {@code null}，此时列表里没有可回落的账号。
     */
    public synchronized BaseAccount defaultAccount() {
        for (BaseAccount account : accounts) {
            if (account.getAccountType() == AccountType.ENV_DEFAULT)
                return account;
        }
        return null;
    }

    public synchronized void move(BaseAccount account, int index) {
        if (account == null || !accounts.remove(account))
            return;
        accounts.add(Math.max(0, Math.min(index, accounts.size())), account);
        orderVersion++;
        markDirty();
    }

    /** 登录/刷新后 storage 变化需要落盘时调用（只标脏，不重建 JSON——对象映射在写时统一做）。 */
    public synchronized void touch(BaseAccount account) {
        if (account != null && accounts.contains(account))
            markDirty();
    }

    /**
     * 写时从对象重建 JSON，并与磁盘上的最新内容合并。
     *
     * <p>合并而不是覆盖，是因为另一个实例可能在我们加载之后加过账号、加过服务器、删过账号：
     * <ul>
     *   <li>磁盘上我们<b>不认识</b>、也没被墓碑标记的条目原样带回——"我没见过"不能成为删掉别人的理由；</li>
     *   <li>被墓碑标记的账号不写回——否则删掉的账号会被另一个实例的下一次保存复活；</li>
     *   <li>我们认识的账号按内存顺序写，并戳一个比磁盘更大的 {@code rev}，让对方下次对账时知道谁更新。</li>
     * </ul>
     * 无法映射的历史条目（未知 type / 手工改坏）同样原样保留。</p>
     */
    private record Snapshot(Set<String> written, long serverOrderVersion) {
    }

    private Snapshot writeSnapshot(JsonObject diskRaw, String freshlyRotatedKey) {
        JsonArray diskAccounts = arrayOf(diskRaw, "accounts");
        Set<String> tombstoned = new LinkedHashSet<>(tombstoneKeys(diskRaw));
        tombstoned.addAll(pendingRemovals.keySet());
        Map<String, Long> diskTombRevs = tombstoneRevs(diskRaw);

        Map<String, JsonObject> diskByKey = new LinkedHashMap<>();
        for (JsonElement element : diskAccounts) {
            if (element instanceof JsonObject jo) {
                String key = AccountKey.of(jo);
                if (key != null)
                    diskByKey.put(key, jo);
            }
        }

        JsonArray next = new JsonArray();
        Set<String> written = new LinkedHashSet<>();
        for (BaseAccount account : accounts) {
            if (account.getAccountType() == AccountType.ENV_DEFAULT)
                continue;
            String key = AccountKey.of(account);
            if (key == null) {
                // 没有稳定身份（缺 uuid）：无法对账，原样写，至少不丢
                next.add(toJson(account));
                continue;
            }
            if (refreshingKeys.contains(key) && !key.equals(freshlyRotatedKey))
                continue; // 保留磁盘条目，等持有账号租约的任务写完换票结果。
            if (tombstoned.contains(key)) {
                // 本实例重新添加过的账号：撤销墓碑（磁盘上的那条也要删掉），否则下次保存又被挡住。
                if (!revivedAccounts.containsKey(key))
                    continue;
                tombstoned.remove(key);
            }
            JsonObject jo = toJson(account);
            JsonObject previous = diskByKey.get(key);
            // 复用同一个 key 时磁盘条目可能已被删掉：此时 rev 的下界要算上删除前的高水位，
            // 否则重加账号的 rev 从 1 重新开始，对端那份 rev=N 的旧快照会被当成更新的版本采纳。
            long floor = Math.max(revisions.getOrDefault(key, 0L), longValue(previous == null ? null : previous.get("rev")));
            floor = Math.max(floor, Math.max(removedRevs.getOrDefault(key, 0L), diskTombRevs.getOrDefault(key, 0L)));
            long rev = floor + 1L;
            jo.addProperty("rev", rev);
            written.add(key);
            next.add(jo);
        }
        for (Map.Entry<String, JsonObject> entry : diskByKey.entrySet()) {
            if (!written.contains(entry.getKey()) && !tombstoned.contains(entry.getKey()))
                next.add(entry.getValue());
        }
        // 认不出 key 的条目（未知 type / 手工改坏）一样保留：既包括内存里原有的，也包括磁盘上新出现的。
        // 这些条目无法对账，删掉就等于替用户丢掉他升级/降级版本时的数据。
        Set<String> unknownSeen = new LinkedHashSet<>();
        for (JsonElement element : root.accounts()) {
            if (element instanceof JsonObject jo && AccountKey.of(jo) == null && unknownSeen.add(jo.toString()))
                next.add(jo);
        }
        for (JsonElement element : diskAccounts) {
            if (element instanceof JsonObject jo && AccountKey.of(jo) == null && unknownSeen.add(jo.toString()))
                next.add(jo);
        }
        replaceContent(root.accounts(), next);

        // servers：先写我们的注册表，再把别的实例新增、我们还不认识的条目补回去。
        // 被墓碑标记的条目例外——那正是"对端删掉了它，我们的旧副本不能把它写回来"。
        JsonArray servers = root.servers();
        Set<String> serverTombstoned = new LinkedHashSet<>(serverTombstones(diskRaw));
        serverTombstoned.addAll(authServers.localTombstones().removed().keySet());
        List<JsonObject> unknownServers = new ArrayList<>();
        for (JsonElement element : arrayOf(diskRaw, "servers")) {
            if (!(element instanceof JsonObject jo))
                continue;
            String type = str(jo.get("type"));
            AuthServerKind kind = AuthServerKind.fromV4(type != null ? type : str(jo.get("kind")));
            String apiRoot = str(jo.get("apiRoot"));
            if (apiRoot == null)
                apiRoot = str(jo.get("server"));
            boolean tombstone = kind != null && apiRoot != null
                    && serverTombstoned.contains(AuthServer.tombstoneKey(kind, apiRoot));
            if (!tombstone && !hasServer(servers, jo))
                unknownServers.add(jo);
        }
        long serverOrderVersion = authServers.persist(servers);
        for (JsonObject jo : unknownServers) {
            if (!hasServer(servers, jo))
                servers.add(jo);
        }

        writeTombstones(diskRaw, tombstoned, written, authServers.localTombstones());
        // 不在这里清 pendingRemovals：落盘失败时墓碑必须留着，否则下一次写会忘了"这条已删除"，
        // 另一个实例的旧快照就能把它复活。清理交给落盘成功的调用方。
        return new Snapshot(written, serverOrderVersion);
    }

    private static boolean hasServer(JsonArray servers, JsonObject candidate) {
        String apiRoot = str(candidate.get("apiRoot"));
        String type = str(candidate.get("type"));
        for (JsonElement element : servers) {
            if (element instanceof JsonObject jo
                    && Objects.equals(apiRoot, str(jo.get("apiRoot")))
                    && Objects.equals(type, str(jo.get("type"))))
                return true;
        }
        return false;
    }

    private static void replaceContent(JsonArray target, JsonArray source) {
        while (!target.isEmpty())
            target.remove(0);
        for (JsonElement element : source)
            target.add(element);
    }

    private static JsonArray arrayOf(JsonObject raw, String name) {
        if (raw == null)
            return new JsonArray();
        JsonElement element = raw.get(name);
        if (element instanceof JsonArray array)
            return array;
        return new JsonArray();
    }

    /** 磁盘上已有的墓碑 key。 */
    private static Set<String> tombstoneKeys(JsonObject diskRaw) {
        Set<String> keys = new LinkedHashSet<>();
        for (JsonElement element : arrayOf(diskRaw, "removed")) {
            if (element instanceof JsonObject jo) {
                String key = str(jo.get("key"));
                if (key != null)
                    keys.add(key);
            }
        }
        return keys;
    }

    /** 磁盘墓碑里的 rev（key → rev）：该账号被删除时已经到过的最高 revision。 */
    private static Map<String, Long> tombstoneRevs(JsonObject diskRaw) {
        Map<String, Long> revs = new HashMap<>();
        for (JsonElement element : arrayOf(diskRaw, "removed")) {
            if (element instanceof JsonObject jo && str(jo.get("key")) != null)
                revs.merge(str(jo.get("key")), longValue(jo.get("rev")), Math::max);
        }
        return revs;
    }

    /** 墓碑只增不减会让文件一直长；保留最近若干条就够了（旧的复活风险远低于体积成本）。 */
    private void writeTombstones(JsonObject diskRaw, Set<String> keys, Set<String> written,
            AuthServerRegistry.Tombstones serverTombstones) {
        JsonArray removed = new JsonArray();
        Map<String, Long> merged = new LinkedHashMap<>();
        Map<String, Long> revs = new HashMap<>();
        // 全局文件被移走后重建时，仍保留内存已有的删除记录。
        for (JsonElement element : arrayOf(root.raw(), "removed")) {
            if (element instanceof JsonObject jo && str(jo.get("key")) != null) {
                merged.put(str(jo.get("key")), longValue(jo.get("at")));
                revs.merge(str(jo.get("key")), longValue(jo.get("rev")), Math::max);
            }
        }
        for (JsonElement element : arrayOf(diskRaw, "removed")) {
            if (element instanceof JsonObject jo && str(jo.get("key")) != null) {
                merged.put(str(jo.get("key")), longValue(jo.get("at")));
                revs.merge(str(jo.get("key")), longValue(jo.get("rev")), Math::max);
            }
        }
        long now = System.currentTimeMillis();
        // 服务器墓碑与账号墓碑共用 removed 数组，靠 "server:" 前缀区分（见 AuthServer.tombstoneKey）。
        // 磁盘与内存里已有的服务器墓碑原样保留，只把本实例新删掉的并进来。
        merged.putAll(serverTombstones.removed());
        for (String key : keys) {
            merged.putIfAbsent(key, pendingRemovals.getOrDefault(key, now));
            revs.merge(key, removedRevs.getOrDefault(key, 0L), Math::max);
        }
        // 这次真的写进 accounts 的条目不是"已删除"：重新添加的账号必须把它的墓碑一起撤掉，
        // 否则文件里留着墓碑，下次启动读到它照样会被跳过。
        merged.keySet().removeAll(written);
        revs.keySet().removeAll(written);
        // 本实例刚显式加回来的服务器：撤掉它的墓碑，否则下次启动装载时会当成已删除跳过。
        merged.keySet().removeAll(serverTombstones.resurrected());
        revs.keySet().removeAll(serverTombstones.resurrected());
        List<Map.Entry<String, Long>> all = new ArrayList<>(merged.entrySet());
        if (all.size() > MAX_TOMBSTONES)
            all = all.subList(all.size() - MAX_TOMBSTONES, all.size());
        root.raw().add("removed", removed);
        for (Map.Entry<String, Long> entry : all) {
            JsonObject jo = new JsonObject();
            jo.addProperty("key", entry.getKey());
            jo.addProperty("at", entry.getValue());
            // 墓碑带 rev，别的实例删除后再添加同一个账号时才知道该从哪个数字往上戳。
            jo.addProperty("rev", revs.getOrDefault(entry.getKey(), 0L));
            removed.add(jo);
        }
        // 即使墓碑被长度上限裁掉，本进程也记住这个高水位：重加账号仍要从它继续往上戳。
        removedRevs.putAll(revs);
    }

    // ── 标脏合并写 ──

    /** 合并后台保存。取锁失败时，后续变更仍能重新排队。 */
    public synchronized void markDirty() {
        if (writePending || store.isReadOnly())
            return;
        writePending = true;
        TaskScheduler.submit(() -> {
            synchronized (this) {
                writePending = false;
            }
            try {
                writeNow(null);
            } catch (IOException e) {
                LOGGER.error("账号配置保存失败（内存状态不受影响，下次变更触发重试）。", e);
            }
        });
    }

    /** 持有账号租约的工作线程同步保存换票结果，不排入串行队列。 */
    void persistRefresh(String key) throws IOException {
        writeNow(key);
    }

    private void writeNow(String freshlyRotatedKey) throws IOException {
        if (store.isReadOnly())
            throw new IOException("配置处于只读模式，无法保存账号。");
        CoordinationService coordination = this.sync;
        if (coordination == null) {
            writeUnderLock(null, freshlyRotatedKey);
            return;
        }
        try {
            coordination.withConfigLock(() -> {
                writeUnderLock(coordination, freshlyRotatedKey);
                return null;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("获取配置写锁失败，本次保存已放弃。", e);
        }
    }

    private void writeUnderLock(CoordinationService coordination, String freshlyRotatedKey) throws IOException {
        JsonObject diskRaw = readDiskRaw();
        Map<String, Long> removals;
        Map<String, BaseAccount> revivals;
        String snapshotText;
        ReconcileResult reconciled = null;
        long snapshotOrderVersion;
        long snapshotServerOrderVersion;
        synchronized (this) {
            // 全局锁覆盖读-改-写，仓储锁只保护内存变更和序列化，避免阻塞客户端线程。
            if (diskRaw != null)
                reconciled = reconcileWith(diskRaw, null);
            Snapshot snapshot = writeSnapshot(diskRaw, freshlyRotatedKey);
            Set<String> written = snapshot.written();
            removals = Map.copyOf(pendingRemovals);
            revivals = new HashMap<>(revivedAccounts);
            revivals.keySet().retainAll(written);
            snapshotText = top.syshub.accountsx.common.utils.NetworkUtils.GSON.toJson(root.raw());
            snapshotOrderVersion = orderVersion;
            // 用序列化数组时原子捕获的版本，而非保存后再读：两者之间被移位插队也不会错戳。
            snapshotServerOrderVersion = snapshot.serverOrderVersion();
        }
        store.saveSnapshot(snapshotText);
        synchronized (this) {
            for (JsonElement element : root.accounts()) {
                if (element instanceof JsonObject entry) {
                    String key = AccountKey.of(entry);
                    if (key != null && !pendingRemovals.containsKey(key))
                        revisions.merge(key, longValue(entry.get("rev")), Math::max);
                }
            }
            removals.forEach(pendingRemovals::remove);
            revivals.forEach(revivedAccounts::remove);
            // 服务器墓碑意图同样在落盘成功后才清：写失败时留着，下一次写还得挡住复活。
            authServers.forgetLocalIntents();
            // 落盘成功：本次快照里的顺序已经在文件里，之后的对账可以采纳别处的顺序。
            // 用「快照时的版本」而不是当前版本：保存期间又拖过一次时不能把新的意图也标成已落盘。
            persistedOrderVersion = Math.max(persistedOrderVersion, snapshotOrderVersion);
            authServers.markOrderPersisted(snapshotServerOrderVersion);
        }
        if (coordination != null)
            coordination.publishWrite();
        // 落盘成功后才把结论交出去：写失败时内存已经改成"删掉了"，但磁盘还没确认，
        // 此时若已经切走游戏会话，用户会看到账号突然消失。
        // changed 含「采纳了对端的服务器/账号排序」这类纯顺序变化：也要通知界面重建左栏，
        // 否则本地保存吸收对端排序后左栏不刷新。会话重装/回落仍由 handleReconcileResult 按
        // removedCurrent/recredentialedCurrent 单独守卫，这里放宽到 changed 不会误切会话。
        if (reconciled != null && reconciled.changed()) {
            java.util.function.Consumer<ReconcileResult> listener = this.reconcileListener;
            if (listener != null)
                listener.accept(reconciled);
        }
    }

    // ── 新↔旧双向映射 ──

    static String str(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /**
     * v4 JSON → 旧对象（登录/刷新/UI 复用整条旧链路，P3 再换模型）。
     * 未知 type 返回 null；snapshot() 会把这类条目原样带回，不删除。
     */
    static BaseAccount fromJson(JsonObject jo) {
        return fromJson(jo, null);
    }

    static BaseAccount fromJson(JsonObject jo, JsonArray servers) {
        String type = str(jo.get("type"));
        // v4 用短 kind（yggdrasil），旧链路用 configId：两边都认
        AccountType accountType = type == null ? null : byConfigId(type);
        if (accountType == null)
            accountType = byKind(type);
        if (accountType == null)
            return null;
        JsonObject profile = jo.get("profile") instanceof JsonObject p ? p : new JsonObject();
        String name = str(profile.get("name"));
        UUID uuid = parseUuid(str(profile.get("uuid")));
        JsonObject credentials = jo.get("credentials") instanceof JsonObject c ? c : new JsonObject();
        String accessToken = str(credentials.get("accessToken"));
        String avatarKey = str(jo.get("avatarKey"));

        String server = str(jo.get("server"));
        // 3→4 把 tokenEndpoint/clientId 种在 servers[].oauth，账号 credentials 里没有——
        // 按 server 反查服务器条目补全，还原旧链路的 "OAuth {…}" 信封（字段错配见 P2.4-review a1）。
        JsonObject envelope = oauthEnvelope(credentials, server, servers);
        BaseAccount loaded = switch (accountType) {
            case OFFLINE -> new OfflineAccount(accessToken, name, uuid);
            case MICROSOFT -> new MicrosoftAccount(accessToken, name, uuid, null, str(credentials.get("refreshToken")), avatarKey, 0L);
            case AUTHLIB_INJECTOR -> {
                var account = new AuthlibInjectorAccountProvider.AuthlibInjectorAccount(
                        accessToken, name, uuid, server, uuid32(str(profile.get("uuid"))),
                        str(jo.get("accountName")), avatarKey, 0L);
                if (envelope != null)
                    account.setLoginTokenForMigration("OAuth " + envelope, uuid32(str(profile.get("uuid"))));
                // 旧版本创建的账号没有这个字段：留 null，刷新时给出「请重新添加」的可操作提示。
                account.setClientTokenForMigration(str(credentials.get("clientToken")));
                yield account;
            }
            default -> null;
        };
        if (loaded != null) {
            // 缺失/0 表示未知（老文件、Yggdrasil）：保持 0，下次启动靠 validate 判定。
            loaded.setTokenExpiryForLoad(longValue(credentials.get("expiresAt")));
            // 构造器按"刚登录"把状态置成 AUTHORIZED，装载时必须退回去：磁盘上的令牌可能早就过期，
            // 当成已授权会让启动刷新整批跳过（见 BaseAccount#markUnauthorizedForLoad）。
            loaded.markUnauthorizedForLoad();
        }
        return loaded;
    }

    /** 数字字段读取：非数字（缺失、null、字符串）一律按 0 处理。 */
    static long longValue(JsonElement e) {
        return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsLong() : 0L;
    }

    private static AccountType byConfigId(String configId) {
        for (AccountType t : AccountType.CONFIGURABLE_VALUES) {
            if (configId.equals(t.getId()))
                return t;
        }
        return null;
    }

    /** v4 的结构化 OAuth 还原为旧链路信封（P3 换掉 provider 链路后删除）。 */
    private static JsonObject oauthEnvelope(JsonObject credentials, String server, JsonArray servers) {
        if (credentials == null || !"true".equals(String.valueOf(credentials.get("oauth"))))
            return null;
        String refresh = str(credentials.get("refreshToken"));
        if (refresh == null)
            return null;
        // endpoint/clientId 优先读账号字段（仓储自己写出的形状），缺失则按 server 反查 servers[]（3→4 种的位置）
        String endpoint = str(credentials.get("tokenEndpoint"));
        String clientId = str(credentials.get("clientId"));
        if ((endpoint == null || clientId == null) && server != null && servers != null) {
            for (JsonElement element : servers) {
                if (element instanceof JsonObject so
                        && server.equals(str(so.get("apiRoot")))
                        && so.get("oauth") instanceof JsonObject oauth) {
                    if (endpoint == null)
                        endpoint = str(oauth.get("tokenEndpoint"));
                    if (clientId == null)
                        clientId = str(oauth.get("clientId"));
                }
            }
        }
        if (endpoint == null || clientId == null)
            return null;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("token_endpoint", endpoint);
        envelope.addProperty("refresh_token", refresh);
        envelope.addProperty("client_id", clientId);
        return envelope;
    }

    /** v4 存无横线 uuid；旧对象要带横线形式，preferredPlayerUUID 同理。 */
    private static String uuid32(String raw) {
        if (raw == null)
            return null;
        String hex = raw.replace("-", "");
        return hex.length() == 32 ? hex : raw;
    }

    private static AccountType byKind(String kind) {
        if (kind == null)
            return null;
        return switch (kind) {
            case "yggdrasil" -> AccountType.AUTHLIB_INJECTOR;
            case "offline" -> AccountType.OFFLINE;
            case "microsoft" -> AccountType.MICROSOFT;
            default -> null;
        };
    }

    private static UUID parseUuid(String raw) {
        if (raw == null)
            return null;
        try {
            return AccountUUID.parse(raw);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("账号条目 uuid 非法，已按 null 处理：{}", raw);
            return null;
        }
    }

    /**
     * 旧对象 → v4 JSON。v4 type 用短 kind（{@link AccountType#getKind}）；
     * 旧链路读回时 fromJson 对 configId 与 kind 双认，跨版本文件可读。
     */
    static JsonObject toJson(BaseAccount account) {
        JsonObject jo = new JsonObject();
        String kind = account.getAccountType().getKind();
        if (kind == null)
            throw new IllegalArgumentException("环境账号不落盘: " + account);
        jo.addProperty("type", kind);
        BaseAccount.AccountStorage storage = account.getAccountStorage();
        if (storage.getPlayerName() != null || storage.getPlayerUUID() != null) {
            JsonObject profile = new JsonObject();
            if (storage.getPlayerName() != null)
                profile.addProperty("name", storage.getPlayerName());
            if (storage.getPlayerUUID() != null)
                profile.addProperty("uuid", AccountUUID.toMinecraftStyleString(storage.getPlayerUUID()));
            jo.add("profile", profile);
        }
        boolean injector = account instanceof top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
        if (injector) {
            var injectorAccount = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount) account;
            if (injectorAccount.getServer() != null)
                jo.addProperty("server", injectorAccount.getServer());
            if (injectorAccount.getAccountName() != null)
                jo.addProperty("accountName", injectorAccount.getAccountName());
        }
        JsonObject credentials = new JsonObject();
        if (injector) {
            var injectorAccount = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount) account;
            String loginToken = injectorAccount.getLoginToken();
            if (loginToken != null && loginToken.startsWith("OAuth ")) {
                // v4 结构化 OAuth 还原为旧链路的信封：login 时 setLoginProfile 写的就是这个形状
                credentials.addProperty("oauth", true);
                if (storage.getAccessToken() != null)
                    credentials.addProperty("accessToken", storage.getAccessToken());
                try {
                    JsonObject envelope = parseObject(loginToken.substring("OAuth ".length()));
                    if (envelope == null
                            || str(envelope.get("token_endpoint")) == null
                            || str(envelope.get("refresh_token")) == null
                            || str(envelope.get("client_id")) == null)
                        throw new IllegalStateException("bad envelope");
                    credentials.addProperty("tokenEndpoint", str(envelope.get("token_endpoint")));
                    credentials.addProperty("refreshToken", str(envelope.get("refresh_token")));
                    credentials.addProperty("clientId", str(envelope.get("client_id")));
                } catch (RuntimeException e) {
                    LOGGER.warn("内存中的 OAuth 信封已损坏，仅保留 oauth 标记与 accessToken。", e);
                }
            } else if (loginToken != null) {
                credentials.addProperty("accessToken", loginToken);
            } else if (storage.getAccessToken() != null) {
                credentials.addProperty("accessToken", storage.getAccessToken());
            }
            if (injectorAccount.getClientToken() != null)
                credentials.addProperty("clientToken", injectorAccount.getClientToken());
        } else if (storage.getAccessToken() != null) {
            credentials.addProperty("accessToken", storage.getAccessToken());
        }
        if (account instanceof MicrosoftAccount ms && ms.getMicrosoftAccountRefreshToken() != null)
            credentials.addProperty("refreshToken", ms.getMicrosoftAccountRefreshToken());
        if (storage.getExpiresAt() > 0)
            // 可选字段：老版本文件没有它，读回时按 0（未知）处理，因此不需要迁移跳。
            credentials.addProperty("expiresAt", storage.getExpiresAt());
        if (!credentials.isEmpty())
            jo.add("credentials", credentials);
        if (account.getAvatarKey() != null)
            jo.addProperty("avatarKey", account.getAvatarKey());
        return jo;
    }

    // ── 诊断 ──

    ConfigStore store() {
        return store;
    }

    synchronized boolean isWritePending() {
        return writePending;
    }

    /** 测试用：同步触发一次合并写（生产路径只经 add/drop/move/touch 标脏）。 */
    void markDirtyForTest() {
        markDirty();
    }
}
