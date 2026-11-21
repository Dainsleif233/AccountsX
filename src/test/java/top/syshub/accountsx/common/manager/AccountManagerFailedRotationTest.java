package top.syshub.accountsx.common.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;
import top.syshub.accountsx.common.sync.AccountKey;
import top.syshub.accountsx.common.sync.CoordinationService;
import top.syshub.accountsx.common.sync.SyncPaths;
import top.syshub.accountsx.common.task.TaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败的换票要在多实例协调目录里留下正确的痕迹。
 *
 * <p>刷新意图是给崩溃兜底的：它假定「服务端可能已经换票、新凭据还没落盘」。所以只有真的
 * 换到票时才该保留，一个令牌都没换到的失败必须清掉——否则下次启动会误报「令牌可能在未保存
 * 的情况下被轮换过」，把「凭据已失效」这个真实原因盖掉。</p>
 */
class AccountManagerFailedRotationTest {
    private static final String STEVE = "069a79f444e94726a5befca90e38aaf5";

    @TempDir
    Path dir;

    private CoordinationService coordination() {
        return new CoordinationService(dir.resolve("accountsx.json"));
    }

    private static OfflineAccount account() {
        return new OfflineAccount("fake-token", "Steve", UUID.fromString(
                STEVE.substring(0, 8) + "-" + STEVE.substring(8, 12) + "-" + STEVE.substring(12, 16) + "-"
                        + STEVE.substring(16, 20) + "-" + STEVE.substring(20)));
    }

    private Path intentFile(CoordinationService sync, OfflineAccount account) {
        return new SyncPaths(dir.resolve("accountsx.json")).intentFile(AccountKey.of(account));
    }

    @Test
    void failedRotationWithoutNewCredentials_clearsTheIntent() throws Exception {
        CoordinationService sync = coordination();
        OfflineAccount account = account();
        Path intent = intentFile(sync, account);
        sync.recordRefreshIntent(AccountKey.of(account));
        assertThat(intent).exists();
        AtomicInteger persisted = new AtomicInteger();

        AccountManager.settleFailedRotation(account, AccountManager.Credentials.of(account),
                AccountKey.of(account), sync, persisted::incrementAndGet);

        assertThat(intent).doesNotExist();
        assertThat(persisted).hasValue(0);
    }

    @Test
    void failedRotationAfterTheServerRotated_persistsAndKeepsTheIntent() throws Exception {
        CoordinationService sync = coordination();
        OfflineAccount account = account();
        String key = AccountKey.of(account);
        Path intent = intentFile(sync, account);
        sync.recordRefreshIntent(key);
        AccountManager.Credentials before = AccountManager.Credentials.of(account);
        TaskScheduler.submit(() -> account.setProfile("fake-token-rotated", "Steve",
                account.getAccountStorage().getPlayerUUID())).get(10, TimeUnit.SECONDS);
        AtomicInteger persisted = new AtomicInteger();

        AccountManager.settleFailedRotation(account, before, key, sync, persisted::incrementAndGet);

        assertThat(persisted).hasValue(1);
        // 落盘由注入的动作负责；这条路径刻意不清意图，留给下次启动提示重新登录。
        assertThat(intent).exists();
    }

    @Test
    void failedRotationWithoutCoordination_stillReportsRotatedCredentials() throws Exception {
        // 单实例（协调器未启用）也要落盘：换到的票只在内存里，重启就丢。
        OfflineAccount account = account();
        AccountManager.Credentials before = AccountManager.Credentials.of(account);
        TaskScheduler.submit(() -> account.setProfile("fake-token-rotated", "Steve",
                account.getAccountStorage().getPlayerUUID())).get(10, TimeUnit.SECONDS);
        AtomicInteger persisted = new AtomicInteger();

        AccountManager.settleFailedRotation(account, before, null, null, persisted::incrementAndGet);

        assertThat(persisted).hasValue(1);
        assertThat(Files.exists(dir.resolve("accountsx.d"))).isFalse();
    }
}
