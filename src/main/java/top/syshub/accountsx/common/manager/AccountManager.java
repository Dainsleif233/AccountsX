package top.syshub.accountsx.common.manager;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorProfileKeys;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftAccount;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.adapters.api.AccountSession;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.storage.ConfigStore;
import top.syshub.accountsx.common.storage.JsonConfigStore;
import top.syshub.accountsx.common.storage.migration.StandardMigrations;
import top.syshub.accountsx.common.sync.AccountKey;
import top.syshub.accountsx.common.sync.CoordinationService;
import top.syshub.accountsx.common.sync.SyncBusyException;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.utils.Threading;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class AccountManager {
    /**
     * 正在刷新的账号 → 该次刷新的完成信号。
     *
     * <p>启动批次和用户点击可能同时刷新同一个账号：两次刷新会各自拿一个令牌，
     * 后落盘的覆盖先落盘的，而先落盘的那个已经在服务端作废，账号随即失效。
     * 因此同一账号同一时刻只允许一次刷新，后来者等待前一次的结果。</p>
     */
    private static final ConcurrentHashMap<BaseAccount, CountDownLatch> PENDING_REFRESHES =
            new ConcurrentHashMap<>();


    private static AccountRepository repository;

    /** 多实例协调器；{@code null} 表示没有配置文件可用（测试/异常路径），退回单实例语义。 */
    private static CoordinationService coordination;

    /**
     * 账号集合被别的实例改过时通知界面（客户端线程）。
     *
     * <p>界面自己不会轮询磁盘：远端删除一个账号后，如果没人告诉它，列表会一直显示一个已经不存在的行。
     * 用回调而不是让界面去轮询，是为了让"发现"只发生一次。</p>
     */
    private static final List<Runnable> CHANGE_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private AccountManager() {
    }

    static AccountRepository repository() {
        if (repository == null)
            throw new IllegalStateException("AccountManager 未初始化（initialize 未调用）。");
        return repository;
    }

    public static List<BaseAccount> getAccountsView() {
        return repository().view();
    }

    public static BaseAccount getCurrentAccount() {
        return repository().getCurrent();
    }

    public static top.syshub.accountsx.common.auth.server.AuthServerRegistry authServers() {
        return repository().authServers();
    }

    /** 账号是否属于某个外置认证服务器（UI 筛选与「删服务器连带删账号」共用同一判定）。 */
    public static boolean isOnServer(BaseAccount account, String apiRoot) {
        return AccountRepository.isOnServer(account, apiRoot);
    }

    /**
     * 账号在界面上该显示的来源名称：外置账号返回认证服务器名，其余类型返回 {@code null}。
     *
     * <p>外置账号的价值在于「在哪个服务器上」，类型名对所有外置账号都一样、没有信息量；
     * 其余类型（微软 / 离线 / 环境）的来源就是类型本身，所以交给调用方回落。</p>
     */
    public static String serverDisplayName(BaseAccount account) {
        if (account == null || account.getAccountType() != AccountType.AUTHLIB_INJECTOR) {
            return null;
        }
        if (!(account instanceof AbstractInjectorAccount injector)) {
            return null;
        }
        AuthServer server = repository().authServers().byApiRoot(injector.getServer());
        return server == null ? null : server.displayName();
    }

    public static void initialize() {
        JsonConfigStore store = JsonConfigStore.createDefault(StandardMigrations.chain());
        repository = new AccountRepository(store);
        BaseAccount environment = Platforms.getMinecraftPlatform().fromCurrentClient();
        Path legacyConfig = FabricLoader.getInstance().getConfigDir().resolve("accountsx");
        CoordinationService sync = new CoordinationService(store.path());
        coordination = sync;
        repository.attachSync(sync);
        try {
            store.prepareConfigDirectory();
            // 首次创建和迁移也会写配置，必须和已启动实例的保存串行。
            sync.withConfigLock(() -> {
                repository.initialize(environment, legacyConfig);
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            AccountsX.LOGGER.warn("未能锁定全局配置，按只读模式启动。", e);
            store.disableWrites();
            repository.initialize(environment, null);
        }
        sync.setListener(AccountManager::onRemoteChange);
        // 保存路径自己也会对账：它发现当前账号被删掉时同样要回落游戏会话。
        repository.onReconciled(result -> {
            try {
                handleReconcileResult(result);
            } catch (RuntimeException e) {
                AccountsX.LOGGER.warn("处理保存路径的对账结论失败。", e);
            }
        });
        sync.start();
        warnStaleIntents(sync);

        // P3.1：服务器元数据启动预热（串行通道，逐 server 一次；失败标 stale 不阻塞启动）
        repository.warmAuthServers();

        List<BaseAccount> toRefresh = new ArrayList<>();
        for (BaseAccount account : repository.view()) {
            if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                toRefresh.add(account);
            }
        }

        if (!toRefresh.isEmpty()) {
            List<TaskScheduler.Task> refreshTasks = new ArrayList<>(toRefresh.size());
            // 刷新失败在内部被消化成 UNAUTHORIZED，任务本身不会异常，因此单独计数给用户一个交代：
            // 启动刷新在后台跑，用户看不到日志，只会在列表里看到账号变成"未登录"。
            AtomicInteger failed = new AtomicInteger();
            for (BaseAccount account : toRefresh) {
                refreshTasks.add(() -> {
                    refreshAccount(account, false);
                    if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                        failed.incrementAndGet();
                    }
                });
            }
            TaskScheduler.runParallel(refreshTasks).whenComplete((ignored, t) -> {
                if (t != null) {
                    AccountsX.LOGGER.warn("Some accounts failed to refresh during startup.", t);
                }
                int count = failed.get();
                if (count > 0) {
                    AccountsX.LOGGER.warn("{} account(s) could not be refreshed during startup.", count);
                    Platforms.getMinecraftPlatform().showToast(
                            "accountsx.account.fail.title", "accountsx.account.fail.startup_refresh", count);
                }
            });
        }
    }

    /**
     * 上次运行在「已向服务端换票、还没落盘」时退出的账号。
     *
     * <p>这个窗口无法自愈：服务端已经作废旧令牌，本地却没有新令牌。此时直接刷新会拿到 401，
     * 用户看到的是"账号莫名其妙失效"。提前提示一句，比让他在登录失败里猜要好。</p>
     */
    private static void warnStaleIntents(CoordinationService sync) {
        List<String> stale = sync.staleIntents();
        if (stale.isEmpty()) {
            return;
        }
        int known = 0;
        for (String key : stale) {
            if (repository().findByKey(key) != null) {
                known++;
            }
        }
        if (known > 0) {
            AccountsX.LOGGER.warn("{} account(s) may have rotated their token without persisting it.", known);
            Platforms.getMinecraftPlatform().showToast(
                    "accountsx.account.sync.title", "accountsx.account.sync.stale_intent", known);
        }
    }

    /**
     * 别的实例改过配置后的对账（协调线程 → 串行通道 → 客户端线程）。
     *
     * <p>对账本身要读盘，放在串行通道上是为了与写路径共用同一把仓储锁；界面通知必须回到
     * 客户端线程，因为列表控件不是线程安全的。</p>
     */
    private static void onRemoteChange() {
        TaskScheduler.submit(() -> {
            AccountRepository.ReconcileResult result;
            try {
                result = repository().reconcile();
            } catch (RuntimeException e) {
                AccountsX.LOGGER.warn("多实例对账失败。", e);
                return;
            }
            handleReconcileResult(result);
        });
    }

    /**
     * 处理一次对账结论（轮询与保存路径共用）。
     *
     * <p>保存路径在锁内对账时可能刚刚发现「当前账号已被别的实例删掉」：轮询只看得见最终状态，
     * 中间态会漏掉。因此两条路径都收到同一个回调，界面通知与游戏会话回落只写一份。</p>
     */
    private static void handleReconcileResult(AccountRepository.ReconcileResult result) {
        if (result == null || !result.changed()) {
            return;
        }
        Platforms.getMinecraftPlatform().execute(() -> {
            notifyChangeListeners();
            if (result.recredentialedCurrent() != null) {
                reinstallSession(result.recredentialedCurrent());
            }
            if (result.removedCurrent() != null) {
                // 当前账号被别的客户端删了：回落到默认账号，别把游戏会话留在已删除账号上。
                // 这里不调 signOut——删它的那个实例已经撤销过令牌了。
                announceCurrent(repository().getCurrent());
                switchToFallback();
                Platforms.getMinecraftPlatform().showToast(
                        "accountsx.account.sync.title", "accountsx.account.sync.removed_current",
                        result.removedCurrent().getAccountStorage().getPlayerName());
            }
        });
    }

    /**
     * 当前账号的令牌被别的实例换掉后，把游戏里那条会话也换成新令牌（P3-j）。
     *
     * <p>只改账号模型是不够的：{@code Minecraft.getInstance().getUser()} 里还是旧令牌，下一次
     * 连服务器、验证会话、拉皮肤都会拿它去请求，而服务端在换票时已经把它作废了。</p>
     *
     * <p>重新走一遍「worker 登录 + 客户端线程装会话」：账号此刻是 AUTHORIZED 且未过期，
     * {@link #loginAccount} 不会再换票，只是按新 storage 重建会话对象。装配期间若用户正好
     * 切走了，{@code switchAccount} 会把这个已不是当前的账号装上去——所以装配前再确认一次。</p>
     */
    private static void reinstallSession(BaseAccount account) {
        TaskScheduler.submit(() -> {
            if (repository().getCurrent() != account) {
                return;
            }
            try {
                AccountSession session = loginAccount(account);
                Platforms.getMinecraftPlatform().execute(() -> {
                    if (repository().getCurrent() != account) {
                        return;
                    }
                    switchAccount(account, session);
                });
            } catch (IOException | RuntimeException e) {
                // 装不上不是数据错误：账号本身已经有可用令牌，下一次切号/重连会自然用上。
                AccountsX.LOGGER.warn("当前账号的令牌已被其他客户端更新，但重建游戏会话失败。", e);
            }
        });
    }

    /**
     * 订阅「账号集合被别的实例改动」。必须在客户端线程调用；重复订阅同一实例无副作用。
     */
    public static void addChangeListener(Runnable listener) {
        if (listener != null && !CHANGE_LISTENERS.contains(listener)) {
            CHANGE_LISTENERS.add(listener);
        }
    }

    public static void removeChangeListener(Runnable listener) {
        CHANGE_LISTENERS.remove(listener);
    }

    private static void notifyChangeListeners() {
        for (Runnable listener : CHANGE_LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                // 一个界面刷新失败不能连累其他界面（也更不能连累协调线程）
                AccountsX.LOGGER.warn("Account change listener failed.", e);
            }
        }
    }

    /** 该账号是否正被另一个存活实例当作当前账号（删除前要二次确认）。 */
    public static boolean isUsedByOtherInstance(BaseAccount account) {
        CoordinationService sync = coordination;
        if (sync == null || account == null) {
            return false;
        }
        String key = AccountKey.of(account);
        return key != null && sync.isUsedByOther(key);
    }

    /** 当前账号变化时立刻广播：别的实例的删除确认读到的必须是最新状态。 */
    private static void announceCurrent(BaseAccount account) {
        CoordinationService sync = coordination;
        if (sync != null) {
            sync.publishCurrent(AccountKey.of(account));
        }
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void dropAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        if (account.getAccountType() == AccountType.ENV_DEFAULT) {
            return;
        }

        // drop 之后仓储的 current 会落回默认账号，所以要先把「删的是不是当前账号」问出来。
        boolean wasCurrent = repository().getCurrent() == account;
        repository().drop(account);
        signOut(List.of(account));

        if (wasCurrent) {
            switchToFallback();
        }
        announceCurrent(repository().getCurrent());
    }

    /**
     * 移除认证服务器，并连带删除属于它的账号。
     *
     * <p>服务器条目是账号的登录端点、公钥与 skinDomains 的来源；条目删掉后这些账号既登录不了
     * 也刷新不了，留着只会在列表里占位。当前账号在其中时回落到默认账号（与单条删除同一链路）。</p>
     */
    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void removeServer(AuthServer server) {
        Threading.checkMinecraftClientThread();

        if (server == null) {
            return;
        }

        // 账号对象在 drop 之后就不在列表里了，撤销令牌要用，先取一份。
        List<BaseAccount> affected = repository().accountsOn(server.apiRoot());
        repository().authServers().remove(server.kind(), server.apiRoot());
        if (repository().dropAccountsOn(server.apiRoot())) {
            announceCurrent(repository().getCurrent());
            switchToFallback();
        }
        signOut(affected);
    }

    /**
     * 删除账号后尽力让服务端作废令牌（B13）。
     *
     * <p>投到串行通道：删除本身是客户端线程上的即时操作，撤销是收尾——不能让它阻塞界面，
     * 也不能让网络失败影响删除结果。provider 自己吞掉所有异常（见
     * {@link AccountProvider#signOut}）。</p>
     */
    private static void signOut(List<BaseAccount> accounts) {
        for (BaseAccount account : accounts) {
            TaskScheduler.submit(() -> AccountProvider.getProvider(account).signOut(account));
        }
    }

    /** 当前账号被删后回落到默认账号：worker 上登录，客户端线程上切会话（与点击列表项同一条链路）。 */
    private static void switchToFallback() {
        BaseAccount fallback = repository().getCurrent();
        if (fallback == null) {
            // 没有环境账号可回落：只留下列表，游戏会话维持原样。
            return;
        }

        TaskScheduler.submit(() -> {
            AccountSession session = loginAccount(fallback);
            Platforms.getMinecraftPlatform().execute(() -> switchAccount(fallback, session));
        });
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void addAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        repository().add(account);
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void moveAccount(BaseAccount account, int index) {
        Threading.checkMinecraftClientThread();

        repository().move(account, index);
    }

    /** 左栏服务器排序：delta 为 -1（上移）/ +1（下移），边界处不动。 */
    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void moveServer(AuthServer server, int delta) {
        Threading.checkMinecraftClientThread();

        if (server == null) {
            return;
        }
        repository().authServers().move(server.kind(), server.apiRoot(), delta);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static AccountSession loginAccount(BaseAccount account) throws IOException {
        Threading.checkAccountWorkerThread();

        // 状态是已授权也可能拿到死令牌：游戏开着跨过 expiresAt（微软 24h）后再切回来就是这种情形。
        // 知道过期时刻时直接刷新，别把过期令牌送去 /player/attributes 换一个 401。
        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED || tokenKnownExpired(account)) {
            // 刷新成功后 refreshAccount 自己会把 storage 变更落盘，这里不再重复 touch。
            refreshAccount(account, true);

            // 等待在途刷新时，对方可能已经失败；此时不能再拿旧令牌去构建会话。
            if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                throw new IOException("Account " + account.getAccountType() + " is still unauthorized after refresh");
            }
        }

        AccountContext context = AccountProvider.getProvider(account).createAccountContext(account);

        // 必须在构建会话之前同步：mixin 在会话内随时可能被调用，晚一步就会继续伪造档案密钥对。
        // 离线 / 环境账号没有上下文（provider 返回 null），切过去时必须把标志复位——否则上一个外置
        // 账号留下的 true 会被当成「当前会话也支持」，让下一次伪造行为与实际服务器不符。
        InjectorProfileKeys.setSupported(
                context != null && context.server() != null && context.server().profileKeySupported());

        return Platforms.authlibBridge().createAccountProfile(
                account.getAccountStorage(),
                context,
                Platforms.getMinecraftPlatform().getGameProxy()
        );
    }

    /**
     * 切换当前账号：worker 上登录，客户端线程上切会话，随后在客户端线程回调 {@code onSwitched}。
     *
     * <p>已经是当前账号时不重复登录，直接回调，调用方无需自己判断。</p>
     */
    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void selectAccount(BaseAccount account, Runnable onSwitched) {
        Threading.checkMinecraftClientThread();

        if (account == null) {
            return;
        }

        if (repository().getCurrent() == account) {
            onSwitched.run();
            return;
        }

        TaskScheduler.submit(() -> {
            // 连点两下会排队两次；第二段到这里时已经切完了，不必再登录一次。
            if (repository().getCurrent() == account) {
                Platforms.getMinecraftPlatform().execute(onSwitched);
                return;
            }

            AccountSession session = loginAccount(account);
            Platforms.getMinecraftPlatform().execute(() -> {
                switchAccount(account, session);
                onSwitched.run();
            });
        });
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void switchAccount(BaseAccount account, AccountSession session) {
        Threading.checkMinecraftClientThread();

        repository().setCurrent(account);
        Platforms.getMinecraftPlatform().switchAccount(session);
        // 其他实例按这个记录决定删除前是否提示。
        announceCurrent(account);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void refreshAccount(BaseAccount account, boolean thrown) throws IOException {
        CountDownLatch mine = new CountDownLatch(1);
        CountDownLatch inFlight = PENDING_REFRESHES.putIfAbsent(account, mine);
        if (inFlight != null) {
            // 已有同账号刷新在跑：等它的结果，不重复轮换令牌。
            awaitPendingRefresh(inFlight, thrown);
            return;
        }

        try {
            doRefreshAccount(account);
        } catch (IOException | RuntimeException e) {
            if (thrown)
                throw e;
            AccountsX.LOGGER.warn("账号刷新或保存失败。", e);
        } finally {
            PENDING_REFRESHES.remove(account, mine);
            mine.countDown();
        }
    }

    /**
     * 等待同一账号的在途刷新。
     *
     * <p>完成信号只表示「那次刷新已经结束」，不含失败信息：失败已经由在途那一次自己处理
     * （落状态、打日志，或在 {@code thrown} 时抛给它的调用方）。这里等完后由调用方重新检查
     * 账号状态决定下一步，避免把两个调用方的错误处理纠缠在一起。</p>
     */
    private static void awaitPendingRefresh(CountDownLatch pending, boolean thrown) throws IOException {
        try {
            pending.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (thrown) {
                throw new IOException("Interrupted while waiting for an in-flight refresh", e);
            }
        }
    }

    /**
     * 问 provider「现有令牌还能直接复用吗」（B4）。
     *
     * <p>判定的意外异常一律记成 {@link TokenReuse#UNKNOWN}，<b>不是</b>「不可复用」。退回刷新看起来
     * 更安全，实际相反：刷新会让服务端作废旧令牌，而探测失败（网络抖动、5xx、网关超时）时旧令牌
     * 很可能仍然有效——顺手换票等于把另一个实例刚换好的凭据作废，一次网络故障升级成账号互踢。
     * 「问不出来」就什么都不做，下一轮再问。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static TokenReuse checkTokenReuse(BaseAccount account) {
        try {
            TokenReuse reuse = AccountProvider.getProvider(account).canReuseToken(account);
            return reuse == null ? TokenReuse.UNKNOWN : reuse;
        } catch (IOException | RuntimeException e) {
            AccountsX.LOGGER.debug("Cannot check whether the token can be reused; leaving it alone this round.", e);
            return TokenReuse.UNKNOWN;
        }
    }

    /**
     * 判定为「问不出来」时放弃本轮刷新。
     *
     * <p>不做任何状态改写：state 留在原处（启动时就是 UNAUTHORIZED），下一轮启动或用户重试会
     * 再问一次。这里唯一的动作是留下一条可诊断的日志——静默跳过会让用户以为模组没动。</p>
     */
    private static void abandonRefresh(String key) {
        AccountsX.LOGGER.debug("Token reuse could not be determined; skipping this refresh of {}.", key);
    }

    /** 令牌是否已知过期；{@code expiresAt == 0}（未知）时不作判断，交给 state 与 validate。 */
    private static boolean tokenKnownExpired(BaseAccount account) {
        long expiresAt = account.getAccountStorage().getExpiresAt();
        return expiresAt > 0 && System.currentTimeMillis() >= expiresAt;
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void doRefreshAccount(BaseAccount account) throws IOException {
        CoordinationService sync = coordination;
        String key = AccountKey.of(account);
        if (sync == null || key == null) {
            refreshUnderLock(account, null, key);
            return;
        }
        try {
            sync.withAccountLease(key, CoordinationService.LEASE_TIMEOUT_MILLIS, () -> {
                refreshUnderLock(account, sync, key);
                return null;
            });
        } catch (SyncBusyException e) {
            // 对端可能恰好写完，只尝试采纳磁盘上的令牌。
            if (!Thread.currentThread().isInterrupted()) {
                if (repository().prepareRefresh(account, key)) {
                    TokenReuse reuse = checkTokenReuse(account);
                    if (reuse == TokenReuse.YES) {
                        account.setProfileState(AccountState.AUTHORIZED);
                        return;
                    }
                    if (reuse == TokenReuse.UNKNOWN) {
                        abandonRefresh(key);
                        return;
                    }
                } else {
                    abandonRefresh(key);
                    return;
                }
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot acquire the refresh lease for " + key, e);
        }
    }

    /** 在账号租约内先读盘再判断复用，轮换得到的凭据在释放租约前落盘。 */
    private static void refreshUnderLock(BaseAccount account, CoordinationService sync, String key) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new IOException("Interrupted");

        AccountRepository repository = repository();
        if (sync != null && !repository.prepareRefresh(account, key)) {
            account.setProfileState(AccountState.UNAUTHORIZED);
            return;
        }
        TokenReuse reuse = checkTokenReuse(account);
        if (reuse == TokenReuse.YES) {
            account.setProfileState(AccountState.AUTHORIZED);
            return;
        }
        if (reuse == TokenReuse.UNKNOWN) {
            // 问不出来：不换票、也不改 state。带着旧 state 进游戏比作废有效令牌安全得多。
            abandonRefresh(key);
            return;
        }
        if (key != null && repository.store().isReadOnly())
            throw new IOException("配置处于只读模式，无法保存刷新后的凭据。");
        if (key != null && !repository.beginRotation(account, key))
            return;
        try {
            if (sync != null)
                sync.recordRefreshIntent(key);
            rotate(account, key, sync);
        } finally {
            if (key != null)
                repository.endRotation(key);
        }
    }

    private static void rotate(BaseAccount account, String key, CoordinationService sync) throws IOException {
        Credentials before = Credentials.of(account);
        account.setProfileState(AccountState.AUTHORIZING);
        try {
            AccountProvider.getProvider(account).refresh(account);
        } catch (IOException | RuntimeException e) {
            markUnauthorizedUnlessAlreadyAuthorized(account);
            try {
                settleFailedRotation(account, before, key, sync, () -> persistRotation(key, sync));
            } catch (IOException saveFailure) {
                // 换到了票却没落盘：挂到原始失败上，两个原因都要留在日志里。
                e.addSuppressed(saveFailure);
            }
            throw e;
        }
        persistRotation(key, sync);
    }

    /**
     * 一次失败的换票要在多实例协调目录里留下什么痕迹。
     *
     * <p>「刷新意图」是给崩溃兜底用的：它假定「服务端可能已经换票、新凭据还没落盘」。所以只有
     * 真的换到票时才该保留（落盘失败的话 {@link #persistRotation} 刻意不清）。一个令牌都没换到
     * 的失败（{@code invalid_grant} 一类）必须清掉——否则下次启动会误报「令牌可能在未保存的
     * 情况下被轮换过」，而真实原因是凭据已失效，另有可操作提示。</p>
     */
    static void settleFailedRotation(BaseAccount account, Credentials before, String key, CoordinationService sync,
            RotationSink persist) throws IOException {
        if (!Credentials.of(account).equals(before)) {
            persist.persist();
            return;
        }
        if (sync != null && key != null) {
            sync.clearRefreshIntent(key);
        }
    }

    /** 失败收尾里唯一需要落盘的动作；抽成参数只为让「换到票就该落盘」可被测试。 */
    @FunctionalInterface
    interface RotationSink {
        void persist() throws IOException;
    }

    /**
     * 账号里与「换票」有关的字段快照。
     *
     * <p>用来回答「这次失败的刷新到底换到票没有」：相等就是服务端没动过凭据（刷新在发出请求的
     * 路上就失败了），不等就是新凭据已经进了内存、只是后续步骤把刷新搞崩了。状态（{@code state}）
     * 不参与比较——失败处理本来就会改写它。</p>
     */
    record Credentials(String accessToken, long expiresAt, String loginToken, String clientToken,
            String microsoftRefreshToken) {
        static Credentials of(BaseAccount account) {
            BaseAccount.AccountStorage storage = account.getAccountStorage();
            AbstractInjectorAccount injector = account instanceof AbstractInjectorAccount i ? i : null;
            MicrosoftAccount microsoft = account instanceof MicrosoftAccount m ? m : null;
            return new Credentials(storage.getAccessToken(), storage.getExpiresAt(),
                    injector == null ? null : injector.getLoginToken(),
                    injector == null ? null : injector.getClientToken(),
                    microsoft == null ? null : microsoft.getMicrosoftAccountRefreshToken());
        }
    }

    /**
     * 把刚换到的令牌落盘。
     *
     * <p>失败必须让用户知道：服务端已经作废旧令牌，新令牌只在内存里——这一次游戏里能用，
     * 重启后两个令牌都失效，账号会「莫名其妙又要重新登录」。之前这里只把异常往上抛，
     * 界面照样显示「正常可用」，提示要等到二十天后用户自己发现。</p>
     *
     * <p>不清刷新意图：留着它，下次启动的 stale-intent 提示正好是对的。</p>
     */
    private static void persistRotation(String key, CoordinationService sync) throws IOException {
        if (key == null)
            return;
        try {
            repository().persistRefresh(key);
        } catch (IOException | RuntimeException e) {
            AccountsX.LOGGER.warn("换票成功但未能保存到磁盘，重启后需要重新登录。", e);
            Platforms.getMinecraftPlatform().showToast(
                    "accountsx.account.fail.title", "accountsx.account.fail.rotation_unsaved");
            throw e;
        }
        if (sync != null)
            sync.clearRefreshIntent(key);
    }

    private static void markUnauthorizedUnlessAlreadyAuthorized(BaseAccount account) {
        if (account.getAccountStorage().getState() == AccountState.AUTHORIZING) {
            account.setProfileState(AccountState.UNAUTHORIZED);
        }
    }
}
