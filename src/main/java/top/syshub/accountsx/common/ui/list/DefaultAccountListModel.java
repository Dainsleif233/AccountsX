package top.syshub.accountsx.common.ui.list;

import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.AvatarHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认的 AccountListModel 实现（P5.2）。
 *
 * <p>把原来散落在各 MC 版本列表控件里的登录调度、排序与删除规则收敛到一处：
 * 适配器只需询问 {@link #canMoveUp}/{@link #canMoveDown}/{@link #isRemovable}，
 * 并把点击交给 {@link #onMove}/{@link #onRemove}/{@link #onSelect}。</p>
 */
public class DefaultAccountListModel implements AccountListModel {
    /** 前两位固定：0 是环境账号，1 是首个存储账号，两者都不参与排序。 */
    private static final int FIRST_MOVABLE = 2;

    private boolean reorderable = true;

    /**
     * 二次确认的宽限期：删除一个正被别的客户端使用的账号，窗口内再点一次才真删。
     *
     * <p>删这种账号的后果不止是"本地少一行"：对方正在用它的会话，令牌一被撤销就会掉线。
     * 所以第一次点击只给警告，第二次（3 秒内）才算确认。</p>
     */
    private static final long CONFIRM_WINDOW_MILLIS = 3_000L;

    /** 等待二次确认的账号；{@code null} 表示当前没有待确认的删除。 */
    private BaseAccount pendingRemoval;
    private long pendingRemovalAt;

    @Override
    public void setReorderable(boolean reorderable) {
        this.reorderable = reorderable;
    }

    @Override
    public List<AccountRow> rows() {
        List<AccountRow> result = new ArrayList<>();
        for (BaseAccount account : AccountManager.getAccountsView()) {
            result.add(new AccountRow(
                    account,
                    account.getAccountStorage().getPlayerName(),
                    account.getAccountStorage().getPlayerName(),
                    account.getAccountType().name(),
                    account.getAccountStorage().getState().name(),
                    new AvatarHandle("", 0L),
                    isRemovable(account),
                    canMoveUp(account) || canMoveDown(account)
            ));
        }
        return result;
    }

    @Override
    public void onSelect(BaseAccount account, Runnable onSwitched) {
        clearPendingRemoval();
        AccountManager.selectAccount(account, onSwitched);
    }

    @Override
    public void onMove(BaseAccount account, int delta) {
        clearPendingRemoval();
        if (!reorderable) {
            return;
        }

        int index = indexOf(account);
        if (index < 0) {
            return;
        }

        AccountManager.moveAccount(account, index + delta);
    }

    /**
     * 删除账号；若它正被另一个存活实例使用，则先警告、3 秒内再点一次才真删。
     *
     * <p>判断"是否在用"读的是对方登记的当前账号，切换账号时会立刻更新——否则用户刚在对面
     * 切走，这里还会给出过期的确认提示。</p>
     */
    @Override
    public void onRemove(BaseAccount account) {
        if (account == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean confirming = this.pendingRemoval == account && now - this.pendingRemovalAt <= CONFIRM_WINDOW_MILLIS;
        if (confirming) {
            clearPendingRemoval();
            AccountManager.dropAccount(account);
            return;
        }
        if (AccountManager.isUsedByOtherInstance(account)) {
            this.pendingRemoval = account;
            this.pendingRemovalAt = now;
            Platforms.getMinecraftPlatform().showToast(
                    "accountsx.account.sync.title", "accountsx.account.sync.remove_in_use",
                    account.getAccountStorage().getPlayerName());
            return;
        }
        clearPendingRemoval();
        AccountManager.dropAccount(account);
    }

    /** 任何其他操作都让待确认状态失效：用户改主意了，别让半分钟后的下一次点击变成确认。 */
    public void clearPendingRemoval() {
        this.pendingRemoval = null;
        this.pendingRemovalAt = 0L;
    }

    @Override
    public boolean isRemovable(BaseAccount account) {
        return account.getAccountType() != AccountType.ENV_DEFAULT;
    }

    @Override
    public boolean canMoveUp(BaseAccount account) {
        return reorderable && indexOf(account) >= FIRST_MOVABLE;
    }

    @Override
    public boolean canMoveDown(BaseAccount account) {
        int index = indexOf(account);
        return reorderable && index >= 0 && index < AccountManager.getAccountsView().size() - 1;
    }

    @Override
    public boolean isBusy() {
        return TaskScheduler.isRunning();
    }

    private static int indexOf(BaseAccount account) {
        return AccountManager.getAccountsView().indexOf(account);
    }
}
