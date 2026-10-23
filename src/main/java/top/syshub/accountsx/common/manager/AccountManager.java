package top.syshub.accountsx.common.manager;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.accounts.AccountProvider;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorProfileKeys;
import top.syshub.accountsx.common.accounts.model.AccountState;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.adapters.api.AccountSession;
import top.syshub.accountsx.common.manager.config.ConfigHandle;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.utils.Threading;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AccountManager {
    private static final List<BaseAccount> accounts = new CopyOnWriteArrayList<>();
    private static final List<BaseAccount> readonlyAccounts = Collections.unmodifiableList(accounts);
    private static volatile BaseAccount current = null;

    private AccountManager() {
    }

    public static List<BaseAccount> getAccountsView() {
        return readonlyAccounts;
    }

    public static BaseAccount getCurrentAccount() {
        return current;
    }

    public static void initialize() {
        accounts.add(current = Platforms.getMinecraftPlatform().fromCurrentClient());

        accounts.addAll(ConfigHandle.load());

        List<BaseAccount> toRefresh = new ArrayList<>();
        for (BaseAccount account : accounts) {
            if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
                toRefresh.add(account);
            }
        }

        if (!toRefresh.isEmpty()) {
            List<TaskScheduler.Task> refreshTasks = new ArrayList<>(toRefresh.size());
            AtomicInteger failed = new AtomicInteger();
            for (BaseAccount account : toRefresh) {
                refreshTasks.add(() -> {
                    if (!refreshAccount(account, false)) {
                        failed.incrementAndGet();
                    }
                });
            }
            // 每个账号刷新成功后由 refreshAccount 自行落盘（见该方法注释）：不能只在整批结束时保存，
            // 否则任何一个账号卡住（无超时的半开连接）都会让**其它账号已轮换的新令牌**留在内存里，
            // 退出游戏即丢失，下次启动拿旧令牌刷新必然 403。
            // 这里的收尾 save 只作为兜底（例如所有账号都失败、但内存状态已变成 UNAUTHORIZED 需要落盘）。
            TaskScheduler.runParallel(refreshTasks).whenComplete((ignored, t) -> {
                if (t != null) {
                    AccountsX.LOGGER.warn("Some accounts failed to refresh during startup.", t);
                }
                if (failed.get() > 0) {
                    // 启动失败此前只写日志：用户看到「未登录」却不知道原因，也得不到「重新添加账号」
                    // 这类可操作提示（该提示只在点击账号时出现）。这里给一次汇总提示。
                    AccountsX.LOGGER.warn("{} account(s) could not be refreshed during startup.", failed.get());
                    showToastSafely("accountsx.account.fail.title", "accountsx.account.fail.startup_refresh");
                }
                save();
            });
        } else {
            save();
        }
    }

    /** 与 TaskScheduler 一样容忍适配器不可用（单测环境没有 UI）。 */
    private static void showToastSafely(String titleKey, String descriptionKey) {
        try {
            Platforms.getMinecraftPlatform().showToast(titleKey, descriptionKey);
        } catch (Throwable t) {
            // 适配器不可用；调用方已记录日志。
        }
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void dropAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        if (account.getAccountType() == AccountType.ENV_DEFAULT) {
            return;
        }

        accounts.remove(account);
        save();
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void addAccount(BaseAccount account) {
        Threading.checkMinecraftClientThread();

        accounts.add(account);
        save();
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void moveAccount(BaseAccount account, int index) {
        Threading.checkMinecraftClientThread();

        accounts.remove(account);
        accounts.add(index, account);
        save();
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static AccountSession loginAccount(BaseAccount account) throws IOException {
        Threading.checkAccountWorkerThread();

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            awaitPendingRefresh(account);
        }

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            refreshAccount(account, true);
        }

        AccountContext context = AccountProvider.getProvider(account).createAccountContext(account);
        // mixin 是否注入假档案密钥对取决于该账号的认证服务器能否真实签发（见 InjectorProfileKeys）。
        // 所有切号都经过这里，因此每次登录都会按当前账号刷新标志，不会残留上一个账号的值。
        InjectorProfileKeys.set(context != null && context.server().profileKeySupported());

        return Platforms.authlibBridge().createAccountProfile(
                account.getAccountStorage(),
                context,
                Platforms.getMinecraftPlatform().getGameProxy()
        );
    }

    @Threading.Thread(Threading.ThreadRole.CLIENT)
    public static void switchAccount(BaseAccount account, AccountSession session) {
        Threading.checkMinecraftClientThread();

        current = account;
        Platforms.getMinecraftPlatform().switchAccount(session);
    }

    /**
     * 每个账号至多一个进行中的刷新。
     *
     * <p>启动批次在并行池上刷新账号 X 时，标题屏已经可用，用户点 X 会走串行通道再刷新一次：
     * 两个请求带着同一个旧令牌打向服务端，drasl 的版本号机制只会让其中一个成功，另一个拿到 403。
     * 结果不只是「多点一下没用」——失败方会把状态写成 UNAUTHORIZED（把成功方覆盖掉），
     * 还会弹一条「凭据已失效，请删除后重新添加账号」的误导提示。有了这张表，后来者直接复用
     * 进行中的结果，不再重复请求。</p>
     */
    private static final ConcurrentHashMap<BaseAccount, CompletableFuture<Void>> pendingRefreshes = new ConcurrentHashMap<>();

    /** 只读降级提示只弹一次。 */
    private static final AtomicBoolean readOnlyNoticeShown = new AtomicBoolean();

    /**
     * 刷新一个账号。
     *
     * @return 是否成功（仅在 {@code thrown=false} 时有意义：此时失败不抛出，而是返回 false，
     *         让启动批次能统计失败数量并给用户一次汇总提示）
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static boolean refreshAccount(BaseAccount account, boolean thrown) throws IOException {
        CompletableFuture<Void> inFlight = pendingRefreshes.get(account);
        if (inFlight != null) {
            await(inFlight, thrown);
            return account.getAccountStorage().getState() == AccountState.AUTHORIZED;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> existing = pendingRefreshes.putIfAbsent(account, future);
        if (existing != null) {
            // 竞争失败：另一个线程刚刚开始刷新同一账号，等它的结果即可。
            await(existing, thrown);
            return account.getAccountStorage().getState() == AccountState.AUTHORIZED;
        }

        try {
            boolean success = doRefresh(account, thrown);
            future.complete(null);
            return success;
        } catch (IOException | RuntimeException e) {
            future.completeExceptionally(e);
            throw e;
        } finally {
            pendingRefreshes.remove(account, future);
        }
    }

    /** 等待另一个线程正在进行的刷新；失败时按调用方语义决定是抛出还是只记日志。 */
    private static void await(CompletableFuture<Void> inFlight, boolean thrown) throws IOException {
        try {
            inFlight.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (thrown) {
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IOException("Cannot refresh the account.", cause);
            }
            AccountsX.LOGGER.error("Cannot refresh the account.", cause);
        }
    }

    /** 若该账号已有进行中的刷新则等待其结束（点击登录前调用，避免重复刷新）。 */
    private static void awaitPendingRefresh(BaseAccount account) throws IOException {
        CompletableFuture<Void> inFlight = pendingRefreshes.get(account);
        if (inFlight != null) {
            await(inFlight, true);
        }
    }

    @Threading.Thread(Threading.ThreadRole.WORKER)
    private static boolean doRefresh(BaseAccount account, boolean thrown) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            account.setProfileState(AccountState.UNAUTHORIZED);
            if (thrown) {
                throw new IOException("Interrupted");
            }
            return false;
        }
        account.setProfileState(AccountState.AUTHORIZING);
        try {
            AccountProvider.getProvider(account).refresh(account);
        } catch (IOException e) {
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw e;
            }
            AccountsX.LOGGER.error("Cannot refresh the account.", e);
            return false;
        } catch (RuntimeException e) {
            // 服务端返回畸形 JSON 等情况会以未检查异常冒出来（如 accessToken 缺失导致 NPE）。
            // 状态机只认 IOException，若不在这里归位，账号会永远停在 AUTHORIZING（界面显示「登录中」）。
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw e;
            }
            AccountsX.LOGGER.error("Cannot refresh the account (unexpected error).", e);
            return false;
        }

        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            markUnauthorizedUnlessAlreadyAuthorized(account);
            if (thrown) {
                throw new IOException("Account provider " + account.getAccountType() + " has finished it's refresh invocation, but neither an exception was thrown nor set the account storage to AUTHORIZED");
            }
            return false;
        }

        // 刷新成功即刻落盘：服务端此时已经作废旧 accessToken（Yggdrasil 刷新会轮换，drasl 用版本号
        // 让旧令牌立即失效），若把结果只留在内存里，一次卡住的兄弟账号、一次直接退出游戏，都会让
        // 这份新令牌永久丢失 —— 下次启动拿旧令牌刷新即 403，用户只能重新添加账号。
        save();
        return true;
    }

    /**
     * 刷新失败时把状态归位。仅在当前不是 AUTHORIZED 时才写：并发的两次刷新里，失败的那次
     * 不应该把已经成功的那次（AUTHORIZED）覆盖成未登录。
     */
    private static void markUnauthorizedUnlessAlreadyAuthorized(BaseAccount account) {
        if (account.getAccountStorage().getState() != AccountState.AUTHORIZED) {
            account.setProfileState(AccountState.UNAUTHORIZED);
        }
    }

    private static void save() {
        if (ConfigHandle.isReadOnly()) {
            // 只读降级下写盘被跳过：必须让用户知道，否则他在本次会话里新增/删除的账号会静默消失
            // （下次启动才发现）。只提示一次，避免每次 save 都弹。
            if (readOnlyNoticeShown.compareAndSet(false, true)) {
                try {
                    // 日志与 toast 都可能依赖 Fabric 运行时（AccountsX 实现 ClientModInitializer，
                    // 适配器需要 Loader），单测环境里必须一起容错，不能让「提示」本身抛异常。
                    AccountsX.LOGGER.warn("AccountsX is in read-only mode; account changes will not be saved this session.");
                    Platforms.getMinecraftPlatform().showToast(
                            "accountsx.account.fail.title", "accountsx.account.fail.read_only");
                } catch (Throwable t) {
                    // 适配器/日志不可用（单测环境）；降级本身仍然生效。
                }
            }
            return;
        }

        TaskScheduler.submit(ConfigHandle::write);
    }
}
