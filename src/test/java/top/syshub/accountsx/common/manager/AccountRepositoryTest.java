package top.syshub.accountsx.common.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;
import top.syshub.accountsx.common.storage.ConfigRoot;
import top.syshub.accountsx.common.storage.ConfigStore;
import top.syshub.accountsx.common.storage.JsonConfigStore;
import top.syshub.accountsx.common.storage.migration.MigrationChain;
import top.syshub.accountsx.common.storage.migration.MigrationContext;
import top.syshub.accountsx.common.storage.migration.StandardMigrations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2.4 仓储测试：集合语义、标脏合并写、新旧映射往返、首启动旧配置触发迁移。
 * 全程 jimfs；标脏写走真实 TaskScheduler 串行通道（用轮询等写任务落地）。
 */
class AccountRepositoryTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final FileSystem fs = Jimfs.newFileSystem(
            Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build());
    private final Path global = fs.getPath("/config/accountsx.json");
    private final Path instanceDir = fs.getPath("/game/config/accountsx");
    private final Path legacyHome = fs.getPath("/home/.accountsx");
    private final List<String> writes = new CopyOnWriteArrayList<>();

    /** 记录每次 save 的 accounts 快照（只记 type+uuid，不记 token）。 */
    private ConfigStore store(MigrationChain chain) {
        MigrationContext ctx = new MigrationContext() {
            @Override
            public Path legacyHomeDir() {
                return legacyHome;
            }

            @Override
            public Path avatarCacheDir() {
                return fs.getPath("/home/.cache/accountsx/avatars");
            }
        };
        JsonConfigStore inner = new JsonConfigStore(fs, global, GSON, chain, ctx, (t, d, a) -> {
        });
        return new DelegatingStore(inner);
    }

    /** v4 迁移产物形状的 OAuth 账号：endpoint/clientId 只在 servers[].oauth，不在 credentials。 */
    private static String migratedOauthFixture(String uuid) {
        JsonObject credentials = new JsonObject();
        credentials.addProperty("oauth", true);
        credentials.addProperty("accessToken", "fake-a");
        credentials.addProperty("refreshToken", "fake-r");
        JsonObject account = new JsonObject();
        account.addProperty("type", "yggdrasil");
        account.addProperty("server", "https://example.invalid/api/yggdrasil");
        JsonObject profile = new JsonObject();
        profile.addProperty("name", "Alex");
        profile.addProperty("uuid", uuid);
        account.add("profile", profile);
        account.add("credentials", credentials);
        account.addProperty("avatarKey", "fake-key");
        return account.toString();
    }

    /** 测试用装饰器：记录 save 快照。 */
    private final class DelegatingStore implements ConfigStore {
        private final JsonConfigStore inner;
        /** 非空时把落盘堵住：用来确定性地制造「写进行中又来变更」的窗口。 */
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        DelegatingStore(JsonConfigStore inner) {
            this.inner = inner;
        }

        void blockWrites(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        private void awaitGate() throws IOException {
            CountDownLatch entered = this.entered;
            CountDownLatch release = this.release;
            if (entered == null || release == null)
                return;
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new IOException("测试未放行落盘");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        private void record(com.google.gson.JsonArray accounts) {
            StringBuilder sb = new StringBuilder();
            accounts.forEach(e -> {
                JsonObject jo = e.getAsJsonObject();
                sb.append(str(jo.get("type"))).append('|');
                if (jo.get("profile") instanceof JsonObject p)
                    sb.append(str(p.get("uuid")));
                sb.append(';');
            });
            writes.add(sb.toString());
        }

        @Override
        public ConfigRoot load() {
            return inner.load();
        }

        @Override
        public void save(ConfigRoot root) throws IOException {
            awaitGate();
            record(root.accounts());
            inner.save(root);
        }

        @Override
        public void saveSnapshot(String json) throws IOException {
            awaitGate();
            record(JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("accounts"));
            inner.saveSnapshot(json);
        }

        @Override
        public boolean isReadOnly() {
            return inner.isReadOnly();
        }

        @Override
        public ConfigRoot load(Path legacyGameConfigDir) {
            return inner.load(legacyGameConfigDir);
        }
    }

    private static String str(com.google.gson.JsonElement e) {
        return e instanceof com.google.gson.JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    private static OfflineAccount offline(String name, String uuid32) {
        return new OfflineAccount(null, name, UUID.fromString(toDashed(uuid32)));
    }

    private static AbstractInjectorAccount injector(String name, String server, String uuid32) {
        return new top.syshub.accountsx.common.accounts.impl.injector.impl.AuthlibInjectorAccountProvider
                .AuthlibInjectorAccount("fake-token-" + uuid32, name, UUID.fromString(toDashed(uuid32)),
                        server, uuid32, name, null, 0L);
    }

    private static String toDashed(String uuid32) {
        return uuid32.substring(0, 8) + "-" + uuid32.substring(8, 12) + "-"
                + uuid32.substring(12, 16) + "-" + uuid32.substring(16, 20) + "-" + uuid32.substring(20);
    }

    private DelegatingStore lastStore;

    private AccountRepository repo(MigrationChain chain) {
        lastStore = (DelegatingStore) store(chain);
        AccountRepository repository = new AccountRepository(lastStore);
        repository.initialize(null, null);
        return repository;
    }

    /** 等串行通道把排队的写任务跑完（TaskScheduler 无排空 API，轮询 writes 计数）。 */
    private static void awaitWrites(List<String> writes, int expected, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        while (writes.size() < expected && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        // 只是「等够次数」，不是精确比较：并发下轮询步长可能让它多跑一次写。
        assertThat(writes).as(what).hasSizeGreaterThanOrEqualTo(expected);
    }

    // ── 集合语义 ────────────────────────────────────────────────────

    @Test
    void addDropMove_current() throws Exception {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        OfflineAccount steve = offline("Steve", "069a79f444e94726a5befca90e38aaf5");
        OfflineAccount alex = offline("Alex", "069a79f444e94726a5befca90e38aaf6");

        repository.add(steve);
        repository.add(alex);
        assertThat(repository.view()).containsExactly(steve, alex);

        repository.setCurrent(alex);
        assertThat(repository.getCurrent()).isSameAs(alex);

        repository.move(steve, 1);
        assertThat(repository.view()).containsExactly(alex, steve);

        repository.drop(alex);
        assertThat(repository.view()).containsExactly(steve);

        // 连续变更的合并写次数取决于标脏与写任务启动的竞态（1~4 次都可能），
        // 这里只断言恒真的部分：确实落过盘、且最后一次是终态。
        // 「写进行中的变更只合并成一次」由 mergeWrites_whileOneIsInFlight 确定性验证。
        awaitWrites(writes, 1, "至少落盘 1 次");
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(writes.get(writes.size() - 1))
                .isEqualTo("offline|069a79f444e94726a5befca90e38aaf5;");
    }

    @Test
    void mergeWrites_whileOneIsInFlight() throws Exception {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        lastStore.blockWrites(entered, release);

        OfflineAccount steve = offline("Steve", "069a79f444e94726a5befca90e38aaf5");
        OfflineAccount alex = offline("Alex", "069a79f444e94726a5befca90e38aaf6");

        repository.add(steve);
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("第一次写进入落盘").isTrue();

        // 写进行中：writePending 已被清空，三次变更只排一个写任务
        repository.add(alex);
        repository.move(alex, 0);
        repository.drop(steve);

        release.countDown();
        awaitWrites(writes, 2, "阻塞的写 + 合并的一次");
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(writes).as("写进行中的多次变更只合并成一次落盘").hasSize(2);
        assertThat(writes.get(1)).isEqualTo("offline|069a79f444e94726a5befca90e38aaf6;");
    }

    @Test
    void envAccount_neverPersisted() throws Exception {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount env =
                new top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount(
                        "fake-token", "Player", UUID.randomUUID());
        repository.initialize(env, null);

        assertThat(repository.view()).containsExactly(env);
        assertThat(repository.getCurrent()).isSameAs(env);
        repository.touch(env);
        awaitWrites(writes, 1, "touch 应触发一次落盘");
        assertThat(writes.get(0)).as("环境账号不落盘").isEqualTo("");
    }

    @Test
    void dropCurrent_fallsBackToEnvironmentAccount() throws Exception {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount env =
                new top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount(
                        "fake-token", "Player", UUID.randomUUID());
        repository.initialize(env, null);
        OfflineAccount steve = offline("Steve", "069a79f444e94726a5befca90e38aaf5");
        repository.add(steve);
        repository.setCurrent(steve);

        repository.drop(steve);

        assertThat(repository.view()).containsExactly(env);
        assertThat(repository.getCurrent()).as("当前账号被删 → 指针回到默认（启动器）账号").isSameAs(env);
    }

    @Test
    void dropNonCurrent_keepsCurrent() throws Exception {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        OfflineAccount steve = offline("Steve", "069a79f444e94726a5befca90e38aaf5");
        OfflineAccount alex = offline("Alex", "069a79f444e94726a5befca90e38aaf6");
        repository.add(steve);
        repository.add(alex);
        repository.setCurrent(steve);

        repository.drop(alex);

        assertThat(repository.getCurrent()).as("删的不是当前账号，指针不动").isSameAs(steve);
    }

    @Test
    void dropCurrent_withoutEnvironment_clearsCurrent() {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        OfflineAccount steve = offline("Steve", "069a79f444e94726a5befca90e38aaf5");
        repository.add(steve);
        repository.setCurrent(steve);

        repository.drop(steve);

        assertThat(repository.getCurrent()).as("没有环境账号可回落 → 指针清空").isNull();
    }

    @Test
    void dropAccountsOn_removesOnlyAccountsOfThatServer() throws Exception {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount env =
                new top.syshub.accountsx.common.accounts.impl.env.EnvironmentAccount(
                        "fake-token", "Player", UUID.randomUUID());
        repository.initialize(env, null);
        AbstractInjectorAccount alex = injector("Alex", "https://a.invalid/api/yggdrasil", "069a79f444e94726a5befca90e38aaf5");
        AbstractInjectorAccount steve = injector("Steve", "https://a.invalid/api/yggdrasil", "069a79f444e94726a5befca90e38aaf6");
        AbstractInjectorAccount sam = injector("Sam", "https://b.invalid/api/yggdrasil", "069a79f444e94726a5befca90e38aaf7");
        OfflineAccount off = offline("Off", "069a79f444e94726a5befca90e38aaf8");
        repository.add(alex);
        repository.add(steve);
        repository.add(sam);
        repository.add(off);
        repository.setCurrent(steve);

        assertThat(repository.dropAccountsOn("https://a.invalid/api/yggdrasil"))
                .as("当前账号在该服务器 → 调用方需要切回默认账号").isTrue();

        assertThat(repository.view()).as("只删该服务器的账号，别的服务器与离线账号不动")
                .containsExactly(env, sam, off);
        assertThat(repository.getCurrent()).isSameAs(env);

        awaitWrites(writes, 1, "删账号应落盘");
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(writes.get(writes.size() - 1))
                .contains("069a79f444e94726a5befca90e38aaf7")
                .doesNotContain("069a79f444e94726a5befca90e38aaf5")
                .doesNotContain("069a79f444e94726a5befca90e38aaf6");

        assertThat(repository.dropAccountsOn("https://none.invalid/api/yggdrasil"))
                .as("没有账号属于该服务器").isFalse();
        assertThat(repository.view()).containsExactly(env, sam, off);
    }

    // ── 新旧映射往返 ────────────────────────────────────────────────

    @Test
    void fromJson_unknownTypeKeptButSkipped() {
        AccountRepository repository = repo(MigrationChain.EMPTY);
        JsonObject unknown = JsonParser.parseString(
                "{\"type\":\"future-type\",\"profile\":{\"name\":\"X\"}}").getAsJsonObject();

        assertThat(AccountRepository.fromJson(unknown)).isNull();
    }

    @Test
    void mapping_roundTrip_allShapes() throws Exception {
        String uuid = "069a79f444e94726a5befca90e38aaf5";
        List<String> fixtures = List.of(
                "{\"type\":\"offline\",\"profile\":{\"name\":\"Steve\",\"uuid\":\"" + uuid + "\"}}",
                "{\"type\":\"microsoft\",\"profile\":{\"name\":\"Steve\",\"uuid\":\"" + uuid
                        + "\"},\"credentials\":{\"accessToken\":\"fake-mc\",\"refreshToken\":\"fake-r\"}}",
                migratedOauthFixture(uuid));

        for (String fixture : fixtures) {
            JsonObject v4 = JsonParser.parseString(fixture).getAsJsonObject();
            BaseAccount account = AccountRepository.fromJson(v4);
            assertThat(account).as("fromJson: " + fixture).isNotNull();
            JsonObject back = AccountRepository.toJson(account);
            assertThat(back.get("type").getAsString()).isEqualTo(v4.get("type").getAsString());
            assertThat(back.getAsJsonObject("profile").get("uuid").getAsString()).isEqualTo(uuid);
            if (v4.has("server"))
                assertThat(back.get("server").getAsString()).isEqualTo(v4.get("server").getAsString());
        }
        // clientToken 必须随账号持久化：丢了就是「重启后刷新 403」。
        String clientToken = "0123456789abcdef0123456789abcdef";
        var withToken = injector("Steve", "https://example.invalid/api/yggdrasil", uuid);
        withToken.setClientTokenForMigration(clientToken);
        JsonObject persisted = AccountRepository.toJson(withToken);
        assertThat(persisted.getAsJsonObject("credentials").get("clientToken").getAsString())
                .isEqualTo(clientToken);
        var restored = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount)
                AccountRepository.fromJson(persisted);
        assertThat(restored.getClientToken()).isEqualTo(clientToken);

        // OAuth 信封往返：3→4 不在账号 credentials 放 endpoint/clientId，
        // fromJson 必须按 server 从 servers[] 反查（review a1 的断链回归）。
        com.google.gson.JsonArray servers = JsonParser.parseString(
                "[{\"type\":\"yggdrasil\",\"apiRoot\":\"https://example.invalid/api/yggdrasil\","
                        + "\"oauth\":{\"clientId\":\"1214\",\"tokenEndpoint\":\"https://example.invalid/oauth/token\"}}]")
                .getAsJsonArray();
        JsonObject ygg = JsonParser.parseString(fixtures.get(2)).getAsJsonObject();
        var account = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount)
                AccountRepository.fromJson(ygg, servers);
        assertThat(account.getLoginToken()).startsWith("OAuth ");
        assertThat(account.getLoginToken()).contains("fake-r");
        // 无 servers 反查时还原不出信封：loginToken 回退为 accessToken（密码流语义），
        // 刷新走密码 refresh 而非坏 token；OAuth 标记丢失是已知降级（review a1 场景要求 servers 同行）。
        var bare = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount)
                AccountRepository.fromJson(ygg);
        assertThat(bare.getLoginToken()).isEqualTo("fake-a");
        // 未知 type 条目：snapshot 写回时原样保留（review a2 回归）。
        // 注意顺序：先写全局文件，再 initialize——load() 读到真实内容才会保留。
        Files.createDirectories(global.getParent());
        Files.writeString(global, "{\"version\":4,\"servers\":[],\"accounts\":["
                + "{\"type\":\"future-type\",\"profile\":{\"name\":\"X\"}}]}", StandardCharsets.UTF_8);
        AccountRepository repository = new AccountRepository(store(MigrationChain.EMPTY));
        repository.initialize(null, null);
        assertThat(repository.view()).isEmpty();
        repository.markDirtyForTest();
        awaitWrites(writes, 1, "snapshot 应保留未知条目");
        assertThat(writes.get(writes.size() - 1)).contains("future-type");
    }

    @Test
    void mapping_expiresAtRoundTrips_andStaysOptional() {
        String uuid = "069a79f444e94726a5befca90e38aaf5";
        long expiresAt = System.currentTimeMillis() + 3_600_000L;
        AbstractInjectorAccount account = injector("Steve", "https://example.invalid/api/yggdrasil", uuid);
        // 测试线程不是 worker：走无线程检查的入口（生产里由 fromJson/登录流程在 worker 上写）。
        account.setTokenExpiryForLoad(expiresAt);

        JsonObject json = AccountRepository.toJson(account);
        assertThat(json.getAsJsonObject("credentials").get("expiresAt").getAsLong()).isEqualTo(expiresAt);

        BaseAccount restored = AccountRepository.fromJson(json);
        assertThat(restored).isNotNull();
        assertThat(restored.getAccountStorage().getExpiresAt()).isEqualTo(expiresAt);

        // 未知过期时间不写字段：老配置与「服务端没给 expires_in」都用缺席表达 0。
        AbstractInjectorAccount unknown =
                injector("Alex", "https://example.invalid/api/yggdrasil", "11111111111111111111111111111111");
        JsonObject without = AccountRepository.toJson(unknown);
        assertThat(without.getAsJsonObject("credentials").has("expiresAt")).isFalse();
        assertThat(AccountRepository.fromJson(without).getAccountStorage().getExpiresAt()).isZero();
    }

    @Test
    void fromJson_marksLoadedAccountsUnauthorized_soStartupRefreshRuns() {
        // 回归：账号对象经构造器创建，而构造器按"刚登录成功"置 AUTHORIZED。装载时若不退回
        // UNAUTHORIZED，启动刷新会整批跳过，磁盘上的过期令牌会被直接拿去建会话（切微软账号 401）。
        String uuid = "069a79f444e94726a5befca90e38aaf5";
        AbstractInjectorAccount account = injector("Steve", "https://example.invalid/api/yggdrasil", uuid);
        JsonObject json = AccountRepository.toJson(account);

        BaseAccount restored = AccountRepository.fromJson(json);
        assertThat(restored).isNotNull();
        assertThat(restored.getAccountStorage().getState().name()).isEqualTo("UNAUTHORIZED");
        // 令牌本身照旧保留：刷新失败时用户还能看到条目，重新登录也从这条开始。
        assertThat(restored.getAccountStorage().getAccessToken()).isEqualTo(account.getAccountStorage().getAccessToken());
    }

    @Test
    void initialize_loadsPersistedAccounts() throws Exception {
        Files.createDirectories(global.getParent());
        String uuid = "069a79f444e94726a5befca90e38aaf5";
        Files.writeString(global, "{\"version\":4,\"servers\":[],\"accounts\":["
                + "{\"type\":\"offline\",\"profile\":{\"name\":\"Steve\",\"uuid\":\"" + uuid + "\"}}]}",
                StandardCharsets.UTF_8);

        AccountRepository repository = new AccountRepository(store(MigrationChain.EMPTY));
        repository.initialize(null, null);

        assertThat(repository.view()).hasSize(1);
        assertThat(repository.view().get(0).getAccountStorage().getPlayerName()).isEqualTo("Steve");
    }

    @Test
    void initialize_installsAuthServerRegistry() throws Exception {
        Files.createDirectories(global.getParent());
        String uuid = "069a79f444e94726a5befca90e38aaf5";
        Files.writeString(global, "{\"version\":4,\"servers\":[{\"type\":\"yggdrasil\",\"apiRoot\":\"https://example.invalid/api/yggdrasil\",\"displayName\":\"Example\"}],"
                + "\"accounts\":[{\"type\":\"offline\",\"profile\":{\"name\":\"Steve\",\"uuid\":\"" + uuid + "\"}}]}",
                StandardCharsets.UTF_8);

        AccountRepository repository = new AccountRepository(store(MigrationChain.EMPTY));
        repository.initialize(null, null);

        // P3.1：注册表装载 servers 条目（builtin mojang/offline 不再补齐）
        assertThat(repository.authServers().byApiRoot("https://example.invalid/api/yggdrasil")).isNotNull();
        assertThat(repository.authServers().servers()).hasSize(1);
        // 装载本身不标脏；下一次快照把固定条目写回磁盘
        repository.markDirtyForTest();
        awaitWrites(writes, 1, "注册表装载不应额外触发写");
        JsonObject onDisk = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(onDisk.getAsJsonArray("servers")).hasSize(1);
    }

    // ── 首启动触发迁移 ──────────────────────────────────────────────

    @Test
    void initialize_triggersLegacyMigrationOnFirstRun_real() throws Exception {
        String id = "11111111-2222-3333-4444-555555555555";
        Files.createDirectories(instanceDir);
        Files.writeString(instanceDir.resolve("accounts.json"),
                "{\"version\":3,\"id\":\"" + id + "\"}", StandardCharsets.UTF_8);
        Files.createDirectories(legacyHome);
        Files.writeString(legacyHome.resolve(id + ".json"),
                "[{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\","
                        + "\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}}]",
                StandardCharsets.UTF_8);

        AccountRepository repository = new AccountRepository(
                store(StandardMigrations.chain()));
        repository.initialize(null, instanceDir);

        // 旧账号已并入全局并实例化；载荷被删、实例配置改名
        assertThat(repository.view()).hasSize(1);
        assertThat(repository.view().get(0).getAccountStorage().getPlayerName()).isEqualTo("Steve");
        assertThat(Files.exists(legacyHome.resolve(id + ".json"))).isFalse();
        assertThat(Files.exists(instanceDir.resolve("accounts.json.migrated"))).isTrue();
        JsonObject onDisk = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(onDisk.get("version").getAsInt()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(onDisk.getAsJsonArray("accounts")).hasSize(1);

        // 幂等：第二次 initialize 不再重复并入
        AccountRepository again = new AccountRepository(store(StandardMigrations.chain()));
        again.initialize(null, instanceDir);
        JsonObject againOnDisk = JsonParser.parseString(Files.readString(global, StandardCharsets.UTF_8)).getAsJsonObject();
        assertThat(againOnDisk.getAsJsonArray("accounts")).hasSize(1);
    }

    @Test
    void initialize_skipsTriggerWhenGlobalAlreadyHasAccounts() throws Exception {
        String id = "11111111-2222-3333-4444-555555555555";
        Files.createDirectories(instanceDir);
        Files.writeString(instanceDir.resolve("accounts.json"),
                "{\"version\":3,\"id\":\"" + id + "\"}", StandardCharsets.UTF_8);
        Files.createDirectories(legacyHome);
        Files.writeString(legacyHome.resolve(id + ".json"),
                "[{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\","
                        + "\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}}]",
                StandardCharsets.UTF_8);
        Files.createDirectories(global.getParent());
        String uuid = "069a79f444e94726a5befca90e38aaf5";
        Files.writeString(global, "{\"version\":4,\"servers\":[],\"accounts\":["
                + "{\"type\":\"offline\",\"profile\":{\"name\":\"Migrated\",\"uuid\":\"" + uuid + "\"}}]}",
                StandardCharsets.UTF_8);
        // 全局已有账号则绝不触发：旧载荷与实例配置原样保留，读到的是全局里的 Migrated
        AccountRepository repository = new AccountRepository(store(StandardMigrations.chain()));
        repository.initialize(null, instanceDir);

        assertThat(Files.exists(legacyHome.resolve(id + ".json"))).isTrue();
        assertThat(Files.exists(instanceDir.resolve("accounts.json"))).isTrue();
        assertThat(repository.view()).hasSize(1);
        assertThat(repository.view().get(0).getAccountStorage().getPlayerName()).isEqualTo("Migrated");
    }

    // ── 真样本端到端 ────────────────────────────────────────────────
    // 样本见 REFACTORING/08-config-schema.md：v3 实例配置 + 载荷 → 全局 v4。

    @Test
    void realWorldSample_v3ToV4() throws Exception {
        String id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Files.createDirectories(instanceDir);
        Files.writeString(instanceDir.resolve("accounts.json"),
                "{\"version\":3,\"id\":\"" + id + "\"}", StandardCharsets.UTF_8);
        Files.createDirectories(legacyHome);
        String payload = "["
                + "{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf5\"}},"
                + "{\"type\":\"microsoft\",\"storage\":{\"accessToken\":\"fake-mc\",\"playerName\":\"Sam\",\"playerUUID\":\"069a79f4-44e9-4726-a5befca90e38aaf7\"},"
                + "\"microsoftAccountRefreshToken\":\"fake-r\"},"
                + "{\"type\":\"injector.authlib-injector\",\"storage\":{\"accessToken\":\"fake-y\",\"playerName\":\"Alex\",\"playerUUID\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"},"
                + "\"server\":\"https://example.invalid/api/yggdrasil\",\"loginToken\":\"fake-y\",\"accountName\":\"Example\"}"
                + "]";
        Files.writeString(legacyHome.resolve(id + ".json"), payload, StandardCharsets.UTF_8);

        AccountRepository repository = new AccountRepository(store(StandardMigrations.chain()));
        repository.initialize(null, instanceDir);

        List<BaseAccount> accounts = new ArrayList<>(repository.view());
        assertThat(accounts).hasSize(3);
        List<String> names = new ArrayList<>();
        for (BaseAccount a : accounts)
            names.add(a.getAccountStorage().getPlayerName());
        assertThat(names).containsExactlyInAnyOrder("Steve", "Sam", "Alex");
        // injector 账号 server 还原，登录链路可用
        var ygg = (top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount) accounts.stream()
                .filter(a -> a.getAccountStorage().getPlayerName().equals("Alex")).findFirst().orElseThrow();
        assertThat(ygg.getServer()).isEqualTo("https://example.invalid/api/yggdrasil");
        assertThat(ygg.getLoginToken()).isEqualTo("fake-y");
    }
}
