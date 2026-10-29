package top.syshub.accountsx.common.manager;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorProfileKeys;
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
import top.syshub.accountsx.common.utils.NetworkUtils;
import top.syshub.accountsx.common.utils.Threading;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class AccountManager {
    /**
     * 正在刷新的账号 → 该次刷新的完成信号。
     *
     * <p>启动批次和用户点击可能同时刷新同一个账号：两次刷新会各自拿一个令牌，
     * 后落盘的覆盖先落盘的，而先落盘的那个已经在服务端作废，账号随即失效。
     * 因此同一账号同一时刻只允许一次刷新，后来者等待前一次的结果。</p>
     */
    private static final ConcurrentHashMap<BaseAccount, CompletableFuture<Void>> PENDING_REFRESHES =
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
        JsonConfigStore store = JsonConfigStore.createDefault(StandardMigrations.chain(NetworkUtils.GSON));
        repository = new AccountRepository(store);
        // 环境账号打头（运行时派生，永不落盘），随后是新存储的账号
        repository.initialize(Platforms.getMinecraftPlatform().fromCurrentClient(),
                FabricLoader.getInstance().getConfigDir().resolve("accountsx"));

        startCoordination(store);

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
        // 注意：initialize 末尾不再无条件 save（审计 1.8 的击穿点已随 ConfigHandle 删除）。
        // 迁移写入由 3→4 自己落盘；load 的结果本来就是最新状态，无需回写。
    }

    /**
     * 启动多实例协调（P6）。
     *
     * <p>失败只降级成单实例模式：协调是「让多个实例不互相踩」，不是运行的前提；
     * 拿不到锁目录（只读盘、权限不足）也不该让模组起不来。</p>
     */
    private static void startCoordination(JsonConfigStore store) {
        try {
            CoordinationService sync = new CoordinationService(store.path());
            coordination = sync;
            repository.attachSync(sync);
            sync.setListener(AccountManager::onRemoteChange);
            sync.start();
            warnStaleIntents(sync);
        } catch (RuntimeException e) {
            coordination = null;
            AccountsX.LOGGER.warn("多实例协调启动失败，按单实例模式运行。", e);
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
            if (!result.changed()) {
                return;
            }
            Platforms.getMinecraftPlatform().execute(() -> {
                notifyChangeListeners();
                if (result.removedCurrent() != null) {
                    // 当前账号被别的客户端删了：回落到默认账号，别把游戏会话留在已删除账号上。
                    // 这里不调 signOut——删它的那个实例已经撤销过令牌了。
                    switchToFallback();
                    Platforms.getMinecraftPlatform().showToast(
                            "accountsx.account.sync.title", "accountsx.account.sync.removed_current",
                            result.removedCurrent().getAccountStorage().getPlayerName());
                }
            });
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

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static AccountSession loginAccount(BaseAccount account) throws IOException {
        Threading.checkAccountWorkerThread();

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
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
        // 立刻广播：另一个实例的"删除确认"读的是心跳里的当前账号，晚 2 秒就会给出错误的确认提示。
        announceCurrent(account);
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void refreshAccount(BaseAccount account, boolean thrown) throws IOException {
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> inFlight = PENDING_REFRESHES.putIfAbsent(account, mine);
        if (inFlight != null) {
            // 已有同账号刷新在跑：等它的结果，不重复轮换令牌。
            awaitPendingRefresh(inFlight, thrown);
            return;
        }

        try {
            doRefreshAccount(account, thrown);
        } finally {
            PENDING_REFRESHES.remove(account, mine);
            mine.complete(null);
        }
    }

    /**
     * 等待同一账号的在途刷新。
     *
     * <p>完成信号只表示「那次刷新已经结束」，不含失败信息：失败已经由在途那一次自己处理
     * （落状态、打日志，或在 {@code thrown} 时抛给它的调用方）。这里等完后由调用方重新检查
     * 账号状态决定下一步，避免把两个调用方的错误处理纠缠在一起。</p>
     */
    private static void awaitPendingRefresh(CompletableFuture<Void> pending, boolean thrown) throws IOException {
        try {
            pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (thrown) {
                throw new IOException("Interrupted while waiting for an in-flight refresh", e);
            }
        } catch (ExecutionException e) {
            // 完成信号从不以异常结束；出现即说明内部 bug，记日志而不是静默吞掉。
            AccountsX.LOGGER.warn("In-flight refresh ended exceptionally.", e.getCause());
        }
    }

    /**
     * 问 provider「现有令牌还能直接复用吗」（B4）。
     *
     * <p>任何异常都按「不能复用」处理：判定失败退回刷新，虽然多换一次令牌，但绝不会因为一次
     * 探测失败就把账号留在未授权状态。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static boolean isTokenReusable(BaseAccount account) {
        try {
            return AccountProvider.getProvider(account).canReuseToken(account);
        } catch (IOException | RuntimeException e) {
            AccountsX.LOGGER.debug("Cannot check whether the token can be reused; refreshing instead.", e);
            return false;
        }
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void doRefreshAccount(BaseAccount account, boolean thrown) throws IOException {
        CoordinationService sync = coordination;
        String key = AccountKey.of(account);
        if (sync == null || key == null) {
            refreshUnderLock(account, thrown, null, null);
            return;
        }
        try {
            sync.withAccountLease(key, CoordinationService.LEASE_TIMEOUT_MILLIS,
                    () -> {
                        refreshUnderLock(account, thrown, sync, key);
                        return null;
                    });
        } catch (SyncBusyException e) {
            // 另一个实例正在给这个账号换票：不能跟着换（那会把对方刚拿到的令牌作废）。
            // 它换完会落盘，我们读一次就够——读到能复用的令牌算是这次刷新的结果。
            if (repository().reconcileAccount(key) && isTokenReusable(account)) {
                account.setProfileState(AccountState.AUTHORIZED);
                return;
            }
            AccountsX.LOGGER.warn("另一个实例正在刷新该账号，本次刷新跳过。", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot acquire the refresh lease for " + key, e);
        }
    }

    /**
     * 租约内的刷新（多实例模式下 {@code sync}/{@code key} 非 null）。
     *
     * <p>顺序是硬约束：<b>先对账、再判复用、最后才换票</b>。反过来（先换票后落盘）会让两个实例
     * 各换一份令牌，后换的把先换的作废，用户看到的是"另一个客户端莫名掉线"。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void refreshUnderLock(BaseAccount account, boolean thrown,
                                         CoordinationService sync, String key) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw new IOException("Interrupted");
            } else {
                return;
            }
        }

        if (sync != null) {
            // 已被别的实例删除：不刷新，更不能再落盘——否则删掉的账号会被写回去。
            if (repository().findByKey(key) == null) {
                account.setProfileState(AccountState.UNAUTHORIZED);
                return;
            }
            repository().reconcileAccount(key);
            if (repository().findByKey(key) == null) {
                account.setProfileState(AccountState.UNAUTHORIZED);
                return;
            }
        }

        if (isTokenReusable(account)) {
            // 令牌仍有效：不刷新。refresh 会作废旧令牌（Yggdrasil 刷新即递增版本号），
            // 而账号 state 是 transient 的、每次启动都按未授权处理——无条件刷新等于每启动
            // 一次换一次令牌，同一账号在别的客户端/实例上就会被顶掉（B4/B14）。
            account.setProfileState(AccountState.AUTHORIZED);
            return;
        }

        if (sync != null) {
            // 先留意图：崩溃窗口（服务端已换票、本地还没落盘）只能靠它事后识别
            sync.recordRefreshIntent(key);
        }
        try {
            rotate(account, thrown);
        } finally {
            if (sync != null) {
                sync.clearRefreshIntent(key);
            }
        }
    }

    /** 真正换票：provider.refresh + 状态机归位 + 落盘。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static void rotate(BaseAccount account, boolean thrown) throws IOException {
        account.setProfileState(AccountState.AUTHORIZING);
        try {
            AccountProvider.getProvider(account).refresh(account);
        } catch (IOException e) {
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw e;
            } else {
                AccountsX.LOGGER.error("Cannot refresh the account.", e);
                return;
            }
        } catch (RuntimeException e) {
            // 服务端返回畸形 JSON（例如缺 accessToken 的 500 响应）会以未检查异常冒出来。
            // 状态机只认 IOException 分支，若不在这里归位，账号会永远停在 AUTHORIZING
            // （界面显示「登录中」，用户既点不动也删不掉，只能重启）。
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw e;
            } else {
                AccountsX.LOGGER.error("Cannot refresh the account (unexpected error).", e);
                return;
            }
        }

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            throw new IOException("Account provider " + account.getAccountType() + " has finished it's refresh invocation, but neither an exception was thrown nor set the account storage to AUTHORIZED");
        }

        // 刷新会轮换服务端令牌：这一次写入不是「提前落盘」而是必须——下次启动若仍读到旧令牌，
        // 而旧令牌已被服务端作废，账号就再也登录不上（用户报告的「重启后无法登录」）。
        // 仓储把写合并到串行通道，连续刷新的多个账号只会产生一次真实落盘。
        repository().touch(account);
    }

    /**
     * 刷新失败时把账号标记为未授权，但如果它此刻已经是 AUTHORIZED 就保持原状。
     *
     * <p>同一账号的刷新链路上可能有并发的登录/切换，其中一个刚把状态写成 AUTHORIZED，
     * 另一个的失败分支再把状态翻回 UNAUTHORIZED，会让可用账号在 UI 上显示成掉登录。
     * 失败只负责「不要把未知状态当成已授权」，不负责否认别人的成功。</p>
     */
    private static void markUnauthorizedUnlessAlreadyAuthorized(BaseAccount account) {
        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            account.setProfileState(AccountState.UNAUTHORIZED);
        }
    }
}
