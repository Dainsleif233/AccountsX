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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * <p>写路径是"变更即标脏 + 调度器串行合并写"：{@link #markDirty()} 只在串行通道上
 * 排一次写任务，N 次连续变更合并为一次落盘。读失败/只读降级时 {@link ConfigStore#save}
 * 本来就会丢弃写请求，所以这里不需要再判 {@code isReadOnly}——禁写语义由存储层保证。</p>
 */
public final class AccountRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountRepository.class);

    private final ConfigStore store;
    private volatile ConfigRoot root;
    private final List<BaseAccount> accounts = new ArrayList<>();
    private volatile BaseAccount current;

    /** 已排队但尚未执行的写任务（合并写用；串行通道保序，volatile 可见性足够）。 */
    private volatile boolean writePending;

    /** 服务器注册表（P3.1）：servers 条目的内存视图 + 元数据缓存；变更经 markDirty 落盘。 */
    private final AuthServerRegistry authServers = new AuthServerRegistry(Platforms.getHttpGateway(), this::markDirty);

    /**
     * 多实例协调（P6）；{@code null} 表示单实例模式（单元测试与无配置文件场景）。
     *
     * <p>一旦装上，写路径变成"锁内读盘 → 合并 → 写回"，读路径多一个"用令牌前先对账"。</p>
     */
    private volatile CoordinationService sync;

    /** 每个账号最后一次落盘/采纳的 revision（缺省 0）。用来判断磁盘上的条目是不是比内存里的新。 */
    private final Map<String, Long> revisions = new HashMap<>();

    /** 本地已删除但还没写进文件的墓碑（key → 删除时刻），写入时并进 {@code removed} 数组。 */
    private final Map<String, Long> pendingRemovals = new LinkedHashMap<>();

    /** {@code removed} 数组最多保留的墓碑数：只用于防止"复活"，留太多没必要。 */
    private static final int MAX_TOMBSTONES = 200;

    public AccountRepository(ConfigStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * 启动加载：环境账号打头 + 首启动旧配置触发迁移 + 新存储 load()。
     *
     * <p>顺序刻意是"先触发迁移、再 load"：触发器直接跑 3→4 落盘 v4（若旧实例配置存在
     * 且全局尚无账号）；随后 load() 读到已迁移的 v4，不再走链。load() 的"文件缺失则落盘空 v4"
     * 只发生在无旧配置可迁的真·首启动。重复触发由"全局已有账号则跳过"拦住
     * （3→4 是覆盖写，重跑结果相同）。</p>
     *
     * @param legacyGameConfigDir 旧实例配置目录（{@code <game>/config/accountsx/}），
     *                            触发迁移用；传 null 表示"不要尝试迁移"（测试/无实例目录场景）
     */
    public synchronized void initialize(BaseAccount environment, Path legacyGameConfigDir) {
        accounts.clear();
        if (environment != null)
            accounts.add(environment);
        current = environment;

        boolean migratedNow = triggerLegacyMigration(legacyGameConfigDir);
        // 触发器必须在 load() 之前跑：首启动时全局文件不存在，触发器经
        // MigrationContext.globalConfigFile() 直接落盘 v4；随后 load() 读到已迁移的
        // v4，不再走链、不落空文件覆盖。
        root = store.load();
        if (migratedNow)
            LOGGER.info("启动迁移完成。");
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

        // 注册表接管 servers（P3.1）：装载 + 补齐固定条目 + 安装到 provider 可见的单例槽。
        authServers.load(root.servers());
        AbstractInjectorAccountProvider.installRegistry(authServers);
    }

    /** P3.1：服务器注册表（登录/刷新经注册表使用）。 */
    public AuthServerRegistry authServers() {
        return authServers;
    }

    /**
     * 装上多实例协调（P6）。必须在 {@link #initialize} 之后调用：装载阶段还不知道其他实例存在，
     * 而写路径要等协调器可用了才敢做"读-合并-写"。
     */
    public void attachSync(CoordinationService coordination) {
        this.sync = coordination;
    }

    /** 当前协调器；{@code null} 表示单实例模式。 */
    public CoordinationService sync() {
        return sync;
    }

    /**
     * 启动预热（P3.1）：在串行通道上逐 server 探测元数据（公钥 / skinDomains / OIDC 端点），
     * 失败保留旧条目（stale）、不阻塞。之后登录/刷新只读内存。
     */
    public void warmAuthServers() {
        TaskScheduler.submit(() -> authServers.refreshAll());
    }

    /**
     * 首启动触发：全局文件是"刚落盘的空配置"（无账号）且旧实例配置存在时，
     * 把旧实例配置当作 v3 文档跑一次 3→4。
     * 3→4 是覆盖写（重跑结果相同），所以重复触发安全；全局文件已有账号时直接跳过。
     *
     * <p>全局文件<b>读不懂</b>（手工改坏 / 断电 / 同步冲突）时不在这里拦：照常触发，
     * 由 3→4 覆盖前先整份备份并记 error（见 {@code UnifyAndDecouple.backupIfUnreadable}），
     * 备份失败才放弃覆盖。至于"用户降级到旧版本模组"（{@code version} 高于当前）：
     * 不支持回退，按普通文件对待。</p>
     *
     * @return 是否实际跑过迁移（跑过则 load 结果已过期，调用方需重读）
     */
    private boolean triggerLegacyMigration(Path legacyGameConfigDir) {
        if (legacyGameConfigDir == null)
            return false;
        Path legacyConfig = legacyGameConfigDir.resolve("accounts.json");
        if (!Files.isRegularFile(legacyConfig))
            // 实例配置不存在 = 无旧数据可迁（真·首启动或已删实例目录），直接跳过
            return false;
        try {
            String text = Files.readString(legacyConfig, StandardCharsets.UTF_8);
            JsonObject legacy = parseObject(text);
            if (legacy == null)
                return false;
            if (!(legacy.get("version") instanceof JsonPrimitive vp) || !vp.isNumber()
                    || vp.getAsInt() != 3 || str(legacy.get("id")) == null)
                return false;
            // 全局文件已有账号 = 迁过了（或其他实例迁的），绝不重复触发。
            JsonObject global = readGlobalRaw(store);
            if (global != null && global.get("accounts") instanceof JsonArray ga && !ga.isEmpty())
                return false;
            top.syshub.accountsx.common.storage.migration.StandardMigrations
                    .chain(top.syshub.accountsx.common.utils.NetworkUtils.GSON)
                    .upgrade(legacy, 3, storeContext());
            return true;
        } catch (Exception e) {
            // 触发失败不阻塞启动：旧文件原样保留，下次启动重试；链内部失败同样降级。
            LOGGER.warn("旧实例配置迁移触发失败（旧文件保留，下次启动重试）。", e);
            return false;
        }
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

    /**
     * 直接读全局文件原文（不走 {@link ConfigStore#load}——load 会落盘/迁移/回写，
     * 在触发器判断里用它等于边读边改；且装饰器包装的 store 读的是同一份文件，
     * 路径必须从 {@link #storeContext} 的 {@code globalConfigFile()} 取）。
     */
    private JsonObject readGlobalRaw(ConfigStore store) {
        try {
            java.nio.file.Path global = storeContext().globalConfigFile();
            if (global == null || !Files.isRegularFile(global))
                return null;
            String text = Files.readString(global, StandardCharsets.UTF_8);
            return parseObject(text);
        } catch (Exception e) {
            // 读不懂 = 当作"没有可复用的内容"放行迁移；覆盖前 3→4 会先备份这份文件。
            LOGGER.warn("读取全局配置原文失败，迁移将覆盖该文件（覆盖前会备份）。", e);
            return null;
        }
    }

    private top.syshub.accountsx.common.storage.migration.MigrationContext storeContext() {
        // store 可能是测试包装（装饰器）：JsonConfigStore 要求的是 path()/migrationContext() 两个能力，
        // 不要求具体类——仓储永远经这个小接口拿，不做 instanceof 强转。
        if (store instanceof Contextual contextual)
            return contextual.migrationContext();
        if (store instanceof top.syshub.accountsx.common.storage.JsonConfigStore jcs)
            return jcs.migrationContext();
        throw new IllegalStateException("AccountRepository 需要带 MigrationContext 的 ConfigStore 才能触发迁移");
    }

    /** 能提供迁移执行环境的 ConfigStore（JsonConfigStore 原生实现；测试装饰器按需实现）。 */
    interface Contextual {
        top.syshub.accountsx.common.storage.migration.MigrationContext migrationContext();
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
        markDirty();
    }

    public synchronized void drop(BaseAccount account) {
        if (account == null || !accounts.remove(account))
            return;
        // 墓碑：删掉的账号可能还躺在另一个实例的内存里，它下一次保存必须知道"这条已经删了"，
        // 否则合并写会把它复活。
        String key = AccountKey.of(account);
        if (key != null) {
            pendingRemovals.put(key, System.currentTimeMillis());
            revisions.remove(key);
        }
        // 删掉的正是当前账号：指针立刻落回默认账号（启动器会话），别留着指向已删除的对象。
        // 真正的游戏会话切换由 AccountManager 在 worker 上做——仓储只管状态。
        if (current == account)
            current = defaultAccount();
        markDirty();
    }

    /** 磁盘原文（合并写的基线）；读不到返回 {@code null}，调用方按"没有别人的改动"处理。 */
    private JsonObject readDiskRaw() {
        try {
            return readGlobalRaw(store);
        } catch (RuntimeException e) {
            LOGGER.warn("读取全局配置原文失败，本次按单实例语义写入。", e);
            return null;
        }
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
    public ReconcileResult reconcile() {
        JsonObject disk = readDiskRaw();
        if (disk == null)
            return ReconcileResult.NONE;
        return reconcileWith(disk, null);
    }

    /**
     * 只对账一个账号（刷新取令牌前必须调用）。
     *
     * <p>顺序不可换：先对账再判令牌。反过来的话，第二个实例会拿自己内存里的旧令牌去调 refresh，
     * 把对方刚换到的令牌作废——正是"另一个客户端莫名其妙掉线"的来源。</p>
     *
     * @return true 表示采纳到了更新的令牌（调用方应重新判断是否还需要联网）
     */
    public boolean reconcileAccount(String key) {
        if (key == null)
            return false;
        JsonObject disk = readDiskRaw();
        if (disk == null)
            return false;
        return reconcileWith(disk, key).changed();
    }

    private synchronized ReconcileResult reconcileWith(JsonObject disk, String onlyKey) {
        boolean changed = false;
        BaseAccount removedCurrent = null;

        Map<String, JsonObject> diskByKey = new LinkedHashMap<>();
        Set<String> tombstoned = tombstoneKeys(disk);
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
                // 只在墓碑存在时才算"被删除"：磁盘读不懂/被清空时不能把账号全丢了
                if (!tombstoned.contains(key))
                    continue;
                accounts.remove(account);
                revisions.remove(key);
                if (current == account) {
                    current = defaultAccount();
                    removedCurrent = account;
                }
                changed = true;
                continue;
            }
            long diskRev = longValue(entry.get("rev"));
            if (onlyKey == null && diskRev <= revisions.getOrDefault(key, 0L))
                continue;
            if (adopt(account, entry)) {
                revisions.put(key, diskRev);
                changed = true;
            }
        }

        // 2) 磁盘上别的实例新增的账号：收进内存，界面才能看见
        if (onlyKey == null) {
            Set<String> local = new LinkedHashSet<>();
            for (BaseAccount account : accounts) {
                String key = AccountKey.of(account);
                if (key != null)
                    local.add(key);
            }
            for (Map.Entry<String, JsonObject> entry : diskByKey.entrySet()) {
                if (local.contains(entry.getKey()))
                    continue;
                try {
                    BaseAccount account = fromJson(entry.getValue(), root.servers());
                    if (account != null) {
                        accounts.add(account);
                        revisions.put(entry.getKey(), longValue(entry.getValue().get("rev")));
                        changed = true;
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("对账时跳过无法解析的账号条目。", e);
                }
            }
            // 3) 别的实例新增了认证服务器：注册表跟着磁盘重载（元数据缓存失效，重新探测即可）
            if (!serversMatch(disk)) {
                authServers.load(arrayOf(disk, "servers"));
                AbstractInjectorAccountProvider.installRegistry(authServers);
                warmAuthServers();
                changed = true;
            }
        }

        return new ReconcileResult(changed, removedCurrent);
    }

    private boolean adopt(BaseAccount account, JsonObject entry) {
        JsonObject profile = entry.get("profile") instanceof JsonObject p ? p : null;
        JsonObject credentials = entry.get("credentials") instanceof JsonObject c ? c : null;
        String accessToken = credentials == null ? null : str(credentials.get("accessToken"));
        BaseAccount.AccountStorage storage = account.getAccountStorage();
        if (Objects.equals(accessToken, storage.getAccessToken())
                && longValue(credentials == null ? null : credentials.get("expiresAt")) == storage.getExpiresAt())
            return false;
        UUID uuid = profile == null ? null : parseUuid(str(profile.get("uuid")));
        String name = profile == null ? null : str(profile.get("name"));
        account.adoptFromDisk(accessToken, name != null ? name : storage.getPlayerName(),
                uuid != null ? uuid : storage.getPlayerUUID(),
                longValue(credentials == null ? null : credentials.get("expiresAt")));
        return true;
    }

    private boolean serversMatch(JsonObject disk) {
        JsonArray diskServers = arrayOf(disk, "servers");
        for (JsonElement element : diskServers) {
            if (!(element instanceof JsonObject jo))
                continue;
            if (!hasServer(root.servers(), jo))
                return false;
        }
        return true;
    }

    /** 对账结果：有没有变化、当前账号是否被别处删除（需要切回默认账号）。 */
    public record ReconcileResult(boolean changed, BaseAccount removedCurrent) {
        public static final ReconcileResult NONE = new ReconcileResult(false, null);
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
    private Set<String> writeSnapshot(JsonObject diskRaw) {
        JsonArray diskAccounts = arrayOf(diskRaw, "accounts");
        Set<String> tombstoned = new LinkedHashSet<>(tombstoneKeys(diskRaw));
        tombstoned.addAll(pendingRemovals.keySet());

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
            if (tombstoned.contains(key))
                continue;
            JsonObject jo = toJson(account);
            long rev = Math.max(revisions.getOrDefault(key, 0L), longValue(diskByKey.get(key) == null ? null : diskByKey.get(key).get("rev"))) + 1L;
            jo.addProperty("rev", rev);
            revisions.put(key, rev);
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

        // servers：先写我们的注册表，再把别的实例新增、我们还不认识的条目补回去
        JsonArray servers = root.servers();
        List<JsonObject> unknownServers = new ArrayList<>();
        for (JsonElement element : arrayOf(diskRaw, "servers")) {
            if (element instanceof JsonObject jo && !hasServer(servers, jo))
                unknownServers.add(jo);
        }
        authServers.persist(servers);
        for (JsonObject jo : unknownServers) {
            if (!hasServer(servers, jo))
                servers.add(jo);
        }

        writeTombstones(diskRaw, tombstoned);
        // 不在这里清 pendingRemovals：落盘失败时墓碑必须留着，否则下一次写会忘了"这条已删除"，
        // 另一个实例的旧快照就能把它复活。清理交给落盘成功的调用方。
        return tombstoned;
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
        JsonArray created = new JsonArray();
        if (raw != null)
            raw.add(name, created);
        return created;
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

    /** 墓碑只增不减会让文件一直长；保留最近若干条就够了（旧的复活风险远低于体积成本）。 */
    private void writeTombstones(JsonObject diskRaw, Set<String> keys) {
        JsonArray removed = arrayOf(root.raw(), "removed");
        Map<String, Long> merged = new LinkedHashMap<>();
        // 内存里已有的墓碑也要带上：磁盘这次读失败时，历史删除记录不能就此丢光。
        for (JsonElement element : arrayOf(root.raw(), "removed")) {
            if (element instanceof JsonObject jo && str(jo.get("key")) != null)
                merged.put(str(jo.get("key")), longValue(jo.get("at")));
        }
        for (JsonElement element : arrayOf(diskRaw, "removed")) {
            if (element instanceof JsonObject jo && str(jo.get("key")) != null)
                merged.put(str(jo.get("key")), longValue(jo.get("at")));
        }
        long now = System.currentTimeMillis();
        for (String key : keys)
            merged.putIfAbsent(key, pendingRemovals.getOrDefault(key, now));
        List<Map.Entry<String, Long>> all = new ArrayList<>(merged.entrySet());
        if (all.size() > MAX_TOMBSTONES)
            all = all.subList(all.size() - MAX_TOMBSTONES, all.size());
        while (!removed.isEmpty())
            removed.remove(0);
        for (Map.Entry<String, Long> entry : all) {
            JsonObject jo = new JsonObject();
            jo.addProperty("key", entry.getKey());
            jo.addProperty("at", entry.getValue());
            removed.add(jo);
        }
    }

    // ── 标脏合并写 ──

    /**
     * 在串行通道上排一个写任务；已有一个在排队就不再排（合并）。
     * 写任务执行时先 {@link #snapshot()} 再 {@link ConfigStore#save}，
     * 所以任务排队期间发生的后续变更也会被同一批写带走。
     */
    public void markDirty() {
        synchronized (this) {
            if (writePending)
                return;
            writePending = true;
        }
        TaskScheduler.submit(() -> {
            try {
                writeNow();
            } catch (IOException e) {
                LOGGER.error("账号配置保存失败（内存状态不受影响，下次变更触发重试）。", e);
            }
        });
    }

    /**
     * 真正落盘：多实例模式下先拿全局写锁，再"读盘 → 合并 → 写回"。
     *
     * <p>锁的意义不是防"同时写"（原子替换已经保证了文件不残缺），而是保证
     * <b>读-改-写整个序列</b>不被另一个实例插进中间：否则两边都基于自己加载时的旧内容，
     * 后写的那个会把对方刚加的账号抹掉。</p>
     */
    private void writeNow() throws IOException {
        CoordinationService coordination = this.sync;
        if (coordination == null) {
            writeUnderLock();
            return;
        }
        try {
            coordination.withConfigLock(this::writeUnderLock);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // 拿不到写锁（超时/中断）：本次写丢弃，内存状态仍在，下次变更会再次触发
            throw new IOException("获取配置写锁失败，本次保存已放弃。", e);
        }
    }

    private Object writeUnderLock() throws IOException {
        JsonObject diskRaw = readDiskRaw();
        // 深拷贝快照必须在锁内做：root.accounts() 是 live 数组，锁外序列化会被
        // CLIENT 线程的 add/drop/move 结构性修改撕裂（P2.4-review 竞态项）。
        Set<String> mergedRemovals;
        String snapshotText;
        synchronized (this) {
            writePending = false;
            // 锁序恒为 仓储→注册表：注册表内部的变更回调（markDirty）在注册表锁外触发。
            mergedRemovals = writeSnapshot(diskRaw);
            snapshotText = top.syshub.accountsx.common.utils.NetworkUtils.GSON.toJson(root.raw());
        }
        store.saveSnapshot(snapshotText);
        synchronized (this) {
            for (String key : mergedRemovals)
                pendingRemovals.remove(key);
        }
        coordinationPublish();
        return null;
    }

    private void coordinationPublish() {
        CoordinationService coordination = this.sync;
        if (coordination != null)
            coordination.publishWrite("save", root.accounts().size());
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
