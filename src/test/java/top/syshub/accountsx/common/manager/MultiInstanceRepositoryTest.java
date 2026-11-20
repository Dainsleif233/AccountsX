package top.syshub.accountsx.common.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;
import top.syshub.accountsx.common.storage.JsonConfigStore;
import top.syshub.accountsx.common.storage.migration.MigrationChain;
import top.syshub.accountsx.common.storage.migration.MigrationContext;
import top.syshub.accountsx.common.sync.CoordinationService;
import top.syshub.accountsx.common.task.TaskScheduler;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多实例协作（P6）：两个仓储指向同一份配置文件，模拟两个客户端同时运行。
 *
 * <p>这里不启动 {@link CoordinationService} 的后台线程：协调器的线程只负责"尽快发现"，
 * 而正确性来自写入时持锁合并、用令牌前对账。把线程关掉反而让断言确定。</p>
 */
class MultiInstanceRepositoryTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String STEVE = "069a79f444e94726a5befca90e38aaf5";
    private static final String ALEX = "069a79f444e94726a5befca90e38aaf6";
    private static final String NOTCH = "069a79f444e94726a5befca90e38aaf7";

    @TempDir
    Path dir;

    private JsonConfigStore store(Path config) {
        FileSystem fs = FileSystems.getDefault();
        MigrationContext ctx = new MigrationContext() {
            @Override
            public Path legacyHomeDir() {
                return dir.resolve("home");
            }

            @Override
            public Path avatarCacheDir() {
                return dir.resolve("avatars");
            }
        };
        return new JsonConfigStore(fs, config, GSON, MigrationChain.EMPTY, ctx, (t, d, a) -> {
        });
    }

    /** 一个"客户端实例"：独立的内存状态 + 独立协调器，但共用同一份配置文件。 */
    private AccountRepository instance(Path config) {
        AccountRepository repository = new AccountRepository(store(config));
        repository.initialize(null, null);
        repository.attachSync(new CoordinationService(config));
        return repository;
    }

    private static OfflineAccount offline(String name, String uuid32) {
        return new OfflineAccount(null, name, UUID.fromString(toDashed(uuid32)));
    }

    private static String toDashed(String uuid32) {
        return uuid32.substring(0, 8) + "-" + uuid32.substring(8, 12) + "-"
                + uuid32.substring(12, 16) + "-" + uuid32.substring(16, 20) + "-" + uuid32.substring(20);
    }

    /** 在 worker 线程上换令牌（{@code setProfile} 有线程检查），再标脏落盘。 */
    private static void rotate(AccountRepository repository, String token) throws Exception {
        BaseAccount account = repository.view().get(0);
        TaskScheduler.submit(() -> account.setProfile(token, account.getAccountStorage().getPlayerName(),
                account.getAccountStorage().getPlayerUUID())).get(10, TimeUnit.SECONDS);
        repository.touch(account);
    }

    private static void awaitWrite() throws Exception {
        TaskScheduler.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    @Test
    void mergeWrite_keepsAccountsAddedByAnotherInstance() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        AccountRepository second = instance(config);

        first.add(offline("Steve", STEVE));
        awaitWrite();

        // 第二个实例仍持有启动时的旧快照，此时它写自己的账号
        second.add(offline("Alex", ALEX));
        awaitWrite();

        AccountRepository reloaded = instance(config);
        assertThat(reloaded.view()).extracting(a -> a.getAccountStorage().getPlayerName())
                .containsExactlyInAnyOrder("Steve", "Alex");
    }

    @Test
    void localDrop_isNotResurrectedByAnotherInstance() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        first.add(offline("Steve", STEVE));
        first.add(offline("Alex", ALEX));
        awaitWrite();

        AccountRepository second = instance(config);
        assertThat(second.view()).hasSize(2);

        first.drop(first.view().get(1));
        awaitWrite();

        // 第二个实例内存里还有 Alex；它保存时不能把已删除的账号写回去
        second.add(offline("Notch", NOTCH));
        awaitWrite();

        AccountRepository reloaded = instance(config);
        assertThat(reloaded.view()).extracting(a -> a.getAccountStorage().getPlayerName())
                .containsExactlyInAnyOrder("Steve", "Notch");
    }

    @Test
    void reconcile_dropsAccountDeletedElsewhere() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        first.add(offline("Steve", STEVE));
        awaitWrite();

        AccountRepository second = instance(config);
        BaseAccount gone = second.view().get(0);
        second.setCurrent(gone);

        first.drop(first.view().get(0));
        awaitWrite();

        AccountRepository.ReconcileResult result = second.reconcile();
        assertThat(result.changed()).isTrue();
        assertThat(result.removedCurrent()).isSameAs(gone);
        assertThat(second.view()).isEmpty();
        assertThat(second.getCurrent()).isNull();
    }

    @Test
    void reconcile_adoptsTokenRotatedByAnotherInstance() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        first.add(offline("Steve", STEVE));
        awaitWrite();

        AccountRepository second = instance(config);
        assertThat(second.view()).hasSize(1);

        // 第一个实例刷新拿到新令牌（setProfile 带线程检查，必须走 worker 通道）
        BaseAccount rotated = first.view().get(0);
        TaskScheduler.submit(() -> rotated.setProfile("fake-token-rotated", "Steve",
                UUID.fromString(toDashed(STEVE)))).get(10, TimeUnit.SECONDS);
        first.touch(rotated);
        awaitWrite();

        assertThat(second.prepareRefresh(second.view().get(0), "offline:" + STEVE)).isTrue();
        assertThat(second.view().get(0).getAccountStorage().getAccessToken())
                .isEqualTo("fake-token-rotated");
        // 对账把状态归为已授权：拿到的确实是刚换好的有效令牌
        assertThat(second.view().get(0).getAccountStorage().getState().name()).isEqualTo("AUTHORIZED");
    }

    @Test
    void readdedAccountIsNotBlockedByItsTombstone() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        first.add(offline("Steve", STEVE));
        awaitWrite();

        // 删掉再重新添加同一个账号（同类型同 UUID）：墓碑不能把它永久封死
        first.drop(first.view().get(0));
        awaitWrite();
        first.add(offline("Steve", STEVE));
        awaitWrite();

        AccountRepository reloaded = instance(config);
        assertThat(reloaded.view()).extracting(a -> a.getAccountStorage().getPlayerName())
                .containsExactly("Steve");
    }

    @Test
    void writeAdoptsNewerTokenFromDiskInsteadOfOverwritingIt() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        first.add(offline("Steve", STEVE));
        awaitWrite();

        // 第二个实例此刻手里还是旧令牌
        AccountRepository second = instance(config);
        rotate(first, "fake-token-rotated");
        awaitWrite();

        // 它写一条无关变更时，不能把内存里的旧令牌盖回去
        second.add(offline("Notch", NOTCH));
        awaitWrite();

        AccountRepository reloaded = instance(config);
        BaseAccount steve = reloaded.view().stream()
                .filter(a -> a.getAccountStorage().getPlayerName().equals("Steve"))
                .findFirst().orElseThrow();
        assertThat(steve.getAccountStorage().getAccessToken()).isEqualTo("fake-token-rotated");
    }

    @Test
    void dropThenWrite_doesNotResurrectTheAccountBeforeTheTombstoneLands() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        first.add(offline("Steve", STEVE));
        first.add(offline("Alex", ALEX));
        awaitWrite();

        // 删掉和下一次写之间不给落盘留时间：合并写会先对账，必须知道"这条是本地删的"
        first.drop(first.view().get(1));
        first.add(offline("Notch", NOTCH));
        awaitWrite();

        AccountRepository reloaded = instance(config);
        assertThat(reloaded.view()).extracting(a -> a.getAccountStorage().getPlayerName())
                .containsExactlyInAnyOrder("Steve", "Notch");
    }

    @Test
    void reconcile_ignoresMissingDiskFileInsteadOfDroppingEverything() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        first.add(offline("Steve", STEVE));
        awaitWrite();

        // 模拟读盘失败：对账绝不能因此清空内存里的账号
        java.nio.file.Files.delete(config);

        AccountRepository.ReconcileResult result = first.reconcile();
        assertThat(result.changed()).isFalse();
        assertThat(first.view()).hasSize(1);
    }

    @Test
    void unreadableOrNewerConfigIsNeverReplacedByOngoingInstance() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository repository = instance(config);
        String broken = "{\"version\":";
        Files.writeString(config, broken);
        repository.add(offline("Steve", STEVE));
        awaitWrite();
        assertThat(Files.readString(config)).isEqualTo(broken);
        assertThatThrownBy(() -> repository.prepareRefresh(repository.view().get(0), "offline:" + STEVE))
                .isInstanceOf(java.io.IOException.class);

        for (String version : new String[]{"5", "4.5", "4294967300"}) {
            String unsupported = "{\"version\":" + version + ",\"accounts\":[],\"servers\":[]}";
            Files.writeString(config, unsupported);
            repository.markDirty();
            awaitWrite();
            assertThat(Files.readString(config)).isEqualTo(unsupported);
        }
    }

    @Test
    void failedLockOpenDoesNotPreventLaterWrites() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository repository = instance(config);
        Path lock = repository.sync().paths().configLock();
        Files.createDirectories(lock);
        repository.add(offline("Steve", STEVE));
        awaitWrite();
        assertThat(instance(config).view()).isEmpty();

        Files.delete(lock);
        repository.add(offline("Alex", ALEX));
        awaitWrite();
        assertThat(instance(config).view()).extracting(a -> a.getAccountStorage().getPlayerName())
                .containsExactly("Steve", "Alex");
    }

    @Test
    void refreshPublishesAllCredentialsBeforeNextLeaseHolder() throws Exception {
        Path config = dir.resolve("accountsx.json");
        AccountRepository first = instance(config);
        MicrosoftAccount writer = new MicrosoftAccount("fake-mc-old", "Steve", UUID.fromString(toDashed(STEVE)),
                null, "fake-msa-old", null, 0);
        first.add(writer);
        awaitWrite();
        AccountRepository second = instance(config);
        MicrosoftAccount reader = (MicrosoftAccount) second.view().get(0);
        String key = "microsoft:" + STEVE;

        first.sync().withAccountLease(key, 0, () -> {
            assertThat(first.prepareRefresh(writer, key)).isTrue();
            assertThat(first.beginRotation(writer, key)).isTrue();
            try {
                writer.setMicrosoftAccountToken(null, "fake-msa-new");
                first.add(offline("Alex", ALEX));
                awaitWrite();
                assertThat(instance(config).view().stream().filter(MicrosoftAccount.class::isInstance)
                        .map(MicrosoftAccount.class::cast).findFirst().orElseThrow().getMicrosoftAccountRefreshToken())
                        .isEqualTo("fake-msa-old");
                first.persistRefresh(key);
            } finally {
                first.endRotation(key);
            }
            return null;
        });

        second.sync().withAccountLease(key, 0, () -> {
            assertThat(second.prepareRefresh(reader, key)).isTrue();
            assertThat(reader.getMicrosoftAccountRefreshToken()).isEqualTo("fake-msa-new");
            return null;
        });
    }
}
