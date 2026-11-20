package top.syshub.accountsx.common.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.storage.migration.Migration;
import top.syshub.accountsx.common.storage.migration.MigrationChain;
import top.syshub.accountsx.common.storage.migration.MigrationContext;
import top.syshub.accountsx.common.storage.migration.StandardMigrations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2.2 存储层测试（05 文档 1.3b 的"读失败不覆写"是本类核心断言）。
 * 全程 jimfs，不碰真实磁盘；所有凭据值用 fake- 前缀。
 */
class JsonConfigStoreTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // unix()：POSIX 路径与权限视图，跨宿主平台行为一致（Windows 默认会拒绝 /config/x.json 这类路径）
    private final FileSystem fs = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build());
    private final Path path = fs.getPath("/config/accountsx.json");
    private final Path instanceDir = fs.getPath("/game/config/accountsx");
    private final Path instance = instanceDir.resolve("accounts.json");
    private final Path payload = fs.getPath("/home/.accountsx/fake-id.json");
    private final List<String> toastTitles = new ArrayList<>();

    private JsonConfigStore store(MigrationChain chain) {
        MigrationContext ctx = new MigrationContext() {
            @Override
            public Path legacyHomeDir() {
                return fs.getPath("/home/.accountsx");
            }

            @Override
            public Path avatarCacheDir() {
                return fs.getPath("/home/.cache/accountsx/avatars");
            }
        };
        return new JsonConfigStore(fs, path, GSON, chain, ctx,
                (title, desc, args) -> toastTitles.add(title));
    }

    private void writeLegacy() throws IOException {
        Files.createDirectories(instanceDir);
        Files.writeString(instance, "{\"version\":3,\"id\":\"fake-id\"}");
        Files.createDirectories(payload.getParent());
        Files.writeString(payload, "[{\"type\":\"offline\",\"storage\":{\"playerName\":\"Steve\"}}]");
    }

    private static Function<String, String> envOf(Map<String, String> map) {
        return map::get;
    }

    // ── 路径解析（决策 D3） ──────────────────────────────────────────

    @Test
    void resolvePath_prefersAccountsxConfig() {
        Path p = JsonConfigStore.resolvePath(envOf(Map.of(
                "ACCOUNTSX_CONFIG", "/custom/acc.json",
                "XDG_CONFIG_HOME", "/xdg")), "/home/u", fs);
        assertThat(p).isEqualTo(fs.getPath("/custom/acc.json"));
    }

    @Test
    void resolvePath_fallsBackToXdgThenUserHome() {
        Path xdg = JsonConfigStore.resolvePath(envOf(Map.of("XDG_CONFIG_HOME", "/xdg")), "/home/u", fs);
        assertThat(xdg).isEqualTo(fs.getPath("/xdg/accountsx.json"));

        Path home = JsonConfigStore.resolvePath(envOf(Map.of()), "/home/u", fs);
        assertThat(home).isEqualTo(fs.getPath("/home/u/.config/accountsx.json"));
    }

    @Test
    void resolvePath_blankOverrideTreatedAsUnset() {
        Path p = JsonConfigStore.resolvePath(envOf(Map.of(
                "ACCOUNTSX_CONFIG", "",
                "XDG_CONFIG_HOME", "/xdg")), "/home/u", fs);
        assertThat(p).isEqualTo(fs.getPath("/xdg/accountsx.json"));
    }

    // ── 正常路径 ────────────────────────────────────────────────────

    @Test
    void load_missingFile_returnsEmptyCurrentVersion_notReadOnly() throws IOException {
        ConfigRoot root = store(MigrationChain.EMPTY).load();
        assertThat(root.version()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(root.servers()).isEmpty();
        assertThat(root.accounts()).isEmpty();
        // 首次运行落盘空配置（08 文档）；旧实现的问题不是"写空配置"，而是"读失败后也写"
        assertThat(Files.exists(path)).isTrue();
        assertThat(store(MigrationChain.EMPTY).isReadOnly()).isFalse();
    }

    @Test
    void saveThenLoad_roundTripPreservesServersAccountsAndUnknownFields() throws IOException {
        JsonConfigStore store = store(MigrationChain.EMPTY);
        ConfigRoot root = ConfigRoot.empty(ConfigStore.CURRENT_VERSION);
        root.accounts().add(JsonParser.parseString(
                "{\"type\":\"offline\",\"profile\":{\"name\":\"Steve\"},\"avatarKey\":\"fake-key\"}"));
        root.servers().add(JsonParser.parseString(
                "{\"type\":\"yggdrasil\",\"apiRoot\":\"https://example.invalid/api/yggdrasil\",\"cachedAt\":1}"));
        root.raw().add("extra", JsonParser.parseString("{\"future\":true}")); // 未知字段必须原样回写
        store.save(root);

        ConfigRoot reloaded = store.load();
        assertThat(reloaded.version()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(reloaded.accounts().get(0).getAsJsonObject().get("avatarKey").getAsString()).isEqualTo("fake-key");
        assertThat(reloaded.servers().get(0).getAsJsonObject().get("apiRoot").getAsString())
                .isEqualTo("https://example.invalid/api/yggdrasil");
        assertThat(reloaded.raw().getAsJsonObject("extra").get("future").getAsBoolean()).isTrue();
    }

    @Test
    void save_leavesNoTmpAndTightensPosixPermissions() throws IOException {
        store(MigrationChain.EMPTY).save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));
        assertThat(Files.exists(path.resolveSibling("accountsx.json.tmp"))).isFalse();
        assertThat(Files.getPosixFilePermissions(path))
                .containsExactlyInAnyOrderElementsOf(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.getPosixFilePermissions(path.getParent()))
                .containsExactlyInAnyOrderElementsOf(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void save_leavesExistingSharedParentPermissionsUnchanged() throws IOException {
        var shared = PosixFilePermissions.fromString("rwxrwxr-x");
        Files.createDirectory(path.getParent(), PosixFilePermissions.asFileAttribute(shared));
        Files.createFile(path, PosixFilePermissions.asFileAttribute(shared));

        store(MigrationChain.EMPTY).save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));

        assertThat(Files.getPosixFilePermissions(path.getParent())).isEqualTo(shared);
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        try (var siblings = Files.list(path.getParent())) {
            assertThat(siblings.toList()).containsExactly(path);
        }
    }

    // ── 降级路径：1.3b 防丢数据 ─────────────────────────────────────

    @Test
    void load_corruptFile_backsUpAndNeverOverwrites() throws IOException {
        Files.createDirectories(path.getParent());
        String corrupt = "{ this is not json ]";
        Files.writeString(path, corrupt, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(path);

        writeLegacy();
        JsonConfigStore store = store(StandardMigrations.chain());
        ConfigRoot root = store.load(instanceDir);

        assertThat(root.accounts()).isEmpty();
        assertThat(store.isReadOnly()).isTrue();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");

        // 备份存在且与原文件逐字节一致
        List<Path> backups = findBackups();
        assertThat(backups).hasSize(1);
        assertThat(Files.readAllBytes(backups.get(0))).isEqualTo(originalBytes);
        assertThat(Files.getPosixFilePermissions(backups.get(0)))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        assertThat(Files.exists(instance)).isTrue();
        assertThat(Files.exists(payload)).isTrue();

        // 降级后 save 被丢弃：原文件逐字节不变（审计 1.8 的核心回归）
        store.save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));
        assertThat(Files.readAllBytes(path)).isEqualTo(originalBytes);
    }

    @Test
    void load_nonObjectRoot_degrades() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "[]", StandardCharsets.UTF_8);
        assertThat(store(MigrationChain.EMPTY).load().accounts()).isEmpty();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");
    }

    @Test
    void load_missingVersion_degrades() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"servers\":[]}", StandardCharsets.UTF_8);
        assertThat(store(MigrationChain.EMPTY).load().accounts()).isEmpty();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");
    }

    @Test
    void load_versionNewerThanCurrent_noBackupReadOnlyTooNewToast() throws IOException {
        Files.createDirectories(path.getParent());
        // getAsInt() 会把这个版本截断成当前支持的 4。
        Files.writeString(path, "{\"version\":4294967300,\"servers\":[],\"accounts\":[]}", StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(path);
        writeLegacy();

        JsonConfigStore store = store(StandardMigrations.chain());
        store.load(instanceDir);

        assertThat(Files.exists(instance)).isTrue();
        assertThat(Files.exists(payload)).isTrue();

        assertThat(store.isReadOnly()).isTrue();
        assertThat(toastTitles).containsExactly("accountsx.config.tooNew.title");
        assertThat(findBackups()).isEmpty(); // 文件未损坏，不需要备份
        store.save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));
        assertThat(Files.readAllBytes(path)).isEqualTo(originalBytes);
    }

    @Test
    void load_olderVersionWithoutChain_degradesWithBackup() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":3,\"id\":\"fake-id\"}", StandardCharsets.UTF_8);

        JsonConfigStore store = store(MigrationChain.EMPTY);
        store.load();

        assertThat(store.isReadOnly()).isTrue();
        assertThat(toastTitles).containsExactly("accountsx.config.degraded.title");
        assertThat(findBackups()).hasSize(1);
    }

    @Test
    void load_olderVersionWithChain_upgradesInPlace() throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":3,\"accounts\":[{\"type\":\"offline\"}]}", StandardCharsets.UTF_8);

        Migration fakeV3toV4 = new Migration() {
            @Override
            public int fromVersion() {
                return 3;
            }

            @Override
            public int toVersion() {
                return 4;
            }

            @Override
            public Result apply(JsonObject config, MigrationContext ctx) {
                config.add("servers", new com.google.gson.JsonArray());
                config.remove("accounts");
                config.addProperty("upgraded", true);
                return new Result(config);
            }
        };
        JsonConfigStore store = store(new MigrationChain(List.of(fakeV3toV4)));
        ConfigRoot root = store.load();

        assertThat(store.isReadOnly()).isFalse();
        assertThat(toastTitles).isEmpty();
        assertThat(root.version()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(root.raw().get("upgraded").getAsBoolean()).isTrue();
    }

    @Test
    void load_afterMigration_persistsUpgradedFile() throws IOException {
        // P2.3 回写语义：迁移成功后立刻落盘，不依赖调用方 save() 自愈
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":3,\"accounts\":[{\"type\":\"offline\"}]}", StandardCharsets.UTF_8);

        Migration fakeV3toV4 = new Migration() {
            @Override
            public int fromVersion() {
                return 3;
            }

            @Override
            public int toVersion() {
                return 4;
            }

            @Override
            public Result apply(JsonObject config, MigrationContext ctx) {
                config.addProperty("upgraded", true);
                return new Result(config);
            }
        };
        store(new MigrationChain(List.of(fakeV3toV4))).load();

        ConfigRoot onDisk = new ConfigRoot(JsonParser.parseString(
                Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject());
        assertThat(onDisk.version()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(onDisk.raw().get("upgraded").getAsBoolean()).isTrue();
    }

    @Test
    void startup_importPreservesExistingGlobalServerAndOtherFields() throws IOException {
        writeLegacy();
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":4,\"accounts\":[],\"servers\":[{\"type\":\"custom\"}],\"extra\":true}");

        ConfigRoot loaded = store(StandardMigrations.chain()).load(instanceDir);
        ConfigRoot onDisk = store(MigrationChain.EMPTY).load();

        assertThat(loaded.accounts()).hasSize(1);
        assertThat(onDisk.raw()).isEqualTo(loaded.raw());
        assertThat(onDisk.raw().get("extra").getAsBoolean()).isTrue();
        assertThat(onDisk.servers()).containsExactly(JsonParser.parseString("{\"type\":\"custom\"}"));
    }

    @Test
    void startup_globalV3UpgradeDoesNotRenameUnrelatedLegacyIndex() throws IOException {
        writeLegacy();
        Files.createDirectories(path.getParent());
        Path globalPayload = payload.resolveSibling("fake-global.json");
        Files.copy(payload, globalPayload);
        Files.writeString(path, "{\"version\":3,\"id\":\"fake-global\"}");
        byte[] originalIndex = Files.readAllBytes(instance);

        assertThat(store(StandardMigrations.chain()).load(instanceDir).accounts()).hasSize(1);
        assertThat(Files.readAllBytes(instance)).isEqualTo(originalIndex);
        assertThat(Files.exists(payload)).isTrue();
        assertThat(Files.exists(globalPayload)).isFalse();
        assertThat(Files.exists(instance.resolveSibling("accounts.json.migrated"))).isFalse();
    }

    @Test
    void startup_doesNotCleanLegacyFilesWhenCommitFails() throws IOException {
        writeLegacy();
        byte[] originalIndex = Files.readAllBytes(instance);
        byte[] originalPayload = Files.readAllBytes(payload);
        Migration v3toV4WithBlockedDestination = new Migration() {
            @Override
            public int fromVersion() {
                return 3;
            }

            @Override
            public int toVersion() {
                return 4;
            }

            @Override
            public Result apply(JsonObject config, MigrationContext ctx) throws Exception {
                Result converted = StandardMigrations.chain().upgrade(config, 3, ctx);
                Files.createDirectories(path);
                Files.createFile(path.resolve("blocks-replacement"));
                return converted;
            }
        };
        JsonConfigStore store = store(new MigrationChain(List.of(v3toV4WithBlockedDestination)));

        store.load(instanceDir);

        assertThat(store.isReadOnly()).isTrue();
        assertThat(Files.readAllBytes(instance)).isEqualTo(originalIndex);
        assertThat(Files.readAllBytes(payload)).isEqualTo(originalPayload);
        assertThat(Files.exists(instance.resolveSibling("accounts.json.migrated"))).isFalse();
        try (var siblings = Files.list(path.getParent())) {
            assertThat(siblings.toList()).containsExactly(path);
        }
    }

    @Test
    void startup_nonRegularPayloadLeavesConfigUnchangedAndDisablesWrites() throws IOException {
        writeLegacy();
        Files.delete(payload);
        Files.createDirectory(payload);
        JsonConfigStore store = store(StandardMigrations.chain());

        assertThat(store.load(instanceDir).accounts()).isEmpty();
        assertThat(store.isReadOnly()).isTrue();
        assertThat(Files.isDirectory(payload)).isTrue();
        assertThat(Files.exists(instance)).isTrue();
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void disableWritesLetsStartupReadCurrentConfigWithoutImportingOrWriting() throws IOException {
        writeLegacy();
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{\"version\":4,\"accounts\":[],\"servers\":[{\"type\":\"custom\"}]}");
        byte[] original = Files.readAllBytes(path);
        JsonConfigStore store = store(StandardMigrations.chain());
        store.disableWrites();

        assertThat(store.load(instanceDir).servers()).hasSize(1);
        store.save(ConfigRoot.empty(ConfigStore.CURRENT_VERSION));

        assertThat(Files.readAllBytes(path)).isEqualTo(original);
        assertThat(Files.exists(instance)).isTrue();
        assertThat(Files.exists(payload)).isTrue();
    }

    // ── 安全约束 ────────────────────────────────────────────────────

    @Test
    void configRoot_toStringDoesNotLeakTokens() {
        ConfigRoot root = ConfigRoot.empty(ConfigStore.CURRENT_VERSION);
        root.accounts().add(JsonParser.parseString(
                "{\"credentials\":{\"accessToken\":\"fake-secret-token\"}}"));
        assertThat(root.toString()).doesNotContain("fake-secret-token");
    }

    private List<Path> findBackups() throws IOException {
        try (Stream<Path> s = Files.list(path.getParent())) {
            return s.filter(p -> p.getFileName().toString().startsWith("accountsx.json.bak-")).toList();
        }
    }
}