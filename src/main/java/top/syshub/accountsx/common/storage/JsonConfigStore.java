package top.syshub.accountsx.common.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.storage.migration.Migration;
import top.syshub.accountsx.common.storage.migration.MigrationChain;
import top.syshub.accountsx.common.storage.migration.MigrationContext;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link ConfigStore} 的 JSON 文件实现（P2.2）。
 *
 * <p>文件系统、路径、环境变量、迁移链与提示回调全部注入，可在 jimfs 内存文件系统中
 * 完整测试（含降级路径）；生产入口是 {@link #createDefault(MigrationChain)}。</p>
 *
 * <p>写路径：{@code .tmp} → fsync → {@code ATOMIC_MOVE}（REPLACE_EXISTING），
 * 崩溃或断电不会留下半截配置文件。POSIX 下创建私有配置文件（600）和新目录（700），
 * 保留已有父目录的权限。Windows 上只记一次 warn。</p>
 *
 * <p>读路径的降级语义（修审计 1.8，本类存在的核心原因）：文件存在但读失败 /
 * 解析失败 / 迁移失败时，先备份为 {@code accountsx.json.bak-<时间戳>}，
 * 记 ERROR、向用户 toast，然后进入只读降级模式——此后 {@link #save} 只记 warn
 * 并丢弃。"绝不覆写用户数据"由实现内部保证，不依赖调用方（如旧
 * {@code initialize()} 末尾无条件 {@code save()}）的自觉。</p>
 */
public final class JsonConfigStore implements ConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonConfigStore.class);
    private static final DateTimeFormatter BACKUP_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final FileAttribute<?> PRIVATE_FILE = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------"));
    private static final FileAttribute<?> PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------"));

    /** 面向用户的提示（游戏内 toast）。 */
    @FunctionalInterface
    public interface Notice {
        void show(String titleKey, String descriptionKey, Object... args);
    }

    private final FileSystem fs;
    private final Path path;
    private final Gson gson;
    private final MigrationChain migrations;
    private final MigrationContext migrationContext;
    private final Notice notice;

    private volatile boolean readOnly;
    private boolean permissionsWarned;

    public JsonConfigStore(FileSystem fs,
                           Path path,
                           Gson gson,
                           MigrationChain migrations,
                           MigrationContext migrationContext,
                           Notice notice) {
        this.fs = fs;
        this.path = path;
        this.gson = gson;
        this.migrations = migrations;
        this.migrationContext = migrationContext;
        this.notice = notice;
    }

    /**
     * 生产默认实例：平台文件系统 + D3 路径解析 + 游戏内 toast。
     * 迁移链由调用方传入（生产用 {@code StandardMigrations.chain()}，P2.4 接线；
     * 传 {@link MigrationChain#EMPTY} 时遇到旧版本配置会安全降级而不是覆写）。
     */
    public static JsonConfigStore createDefault(MigrationChain migrations) {
        FileSystem fs = FileSystems.getDefault();
        Path path = resolvePath(System::getenv, System.getProperty("user.home"), fs);
        MigrationContext ctx = new MigrationContext() {
            @Override
            public Path legacyHomeDir() {
                return fs.getPath(System.getProperty("user.home"), ".accountsx");
            }

            @Override
            public Path avatarCacheDir() {
                return fs.getPath(System.getProperty("user.home"), ".cache", "accountsx", "avatars");
            }
        };
        return new JsonConfigStore(fs, path, NetworkUtils.GSON, migrations, ctx,
                (title, desc, args) -> Platforms.getMinecraftPlatform().showToast(title, desc, args));
    }

    /**
     * 决策 D3 的解析顺序：
     * {@code ACCOUNTSX_CONFIG}（绝对路径，最高优先）→ {@code $XDG_CONFIG_HOME/accountsx.json}
     * → {@code ~/.config/accountsx.json}。
     * 空串按"未设置"处理（CI / 某些 shell 会导出空环境变量）。
     */
    public static Path resolvePath(Function<String, String> env, String userHome, FileSystem fs) {
        String override = env.apply("ACCOUNTSX_CONFIG");
        if (override != null && !override.isBlank())
            return fs.getPath(override);
        String xdg = env.apply("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank())
            return fs.getPath(xdg).resolve("accountsx.json");
        return fs.getPath(userHome).resolve(".config").resolve("accountsx.json");
    }

    @Override
    public ConfigRoot load() {
        return load(null);
    }

    @Override
    public synchronized ConfigRoot load(Path legacyGameConfigDir) {
        ConfigRoot fresh = ConfigRoot.empty(CURRENT_VERSION);
        JsonObject raw;
        try {
            raw = readConfig(path);
            if (raw != null) {
                BigDecimal version = configVersion(raw);
                if (version.compareTo(BigDecimal.valueOf(CURRENT_VERSION)) > 0) {
                    degrade(new IllegalStateException("配置版本高于当前支持版本"), false,
                            "accountsx.config.tooNew.title", "accountsx.config.tooNew.desc");
                    return fresh;
                }
                int fileVersion = version.intValueExact();
                if (fileVersion < CURRENT_VERSION) {
                    if (readOnly)
                        return fresh;
                    Migration.Result upgraded = migrations.upgrade(raw, fileVersion, migrationContext);
                    return commitMigration(new ConfigRoot(upgraded.config()), upgraded.legacyPayload(), null);
                }
                requireArray(raw, "accounts");
                requireArray(raw, "servers");
            }
        } catch (Exception e) {
            degrade(e, true, "accountsx.config.degraded.title", "accountsx.config.degraded.desc");
            return fresh;
        }

        ConfigRoot global = raw == null ? fresh : new ConfigRoot(raw);
        if (readOnly)
            return global;
        if (legacyGameConfigDir != null && global.accounts().isEmpty()) {
            Path instance = legacyGameConfigDir.resolve("accounts.json");
            try {
                JsonObject legacy = readConfig(instance);
                if (legacy != null && (raw == null || !Files.isSameFile(path, instance))) {
                    BigDecimal version = configVersion(legacy);
                    if (version.compareTo(BigDecimal.valueOf(CURRENT_VERSION)) < 0) {
                        Migration.Result upgraded = migrations.upgrade(legacy, version.intValueExact(), migrationContext);
                        ConfigRoot imported = new ConfigRoot(upgraded.config());
                        mergeLegacy(global, imported);
                        return commitMigration(global, upgraded.legacyPayload(), instance);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("无法导入旧实例配置 {}，保留原有文件。", instance);
                degrade(e, false, "accountsx.config.degraded.title", "accountsx.config.degraded.desc");
                return global;
            }
        }
        if (raw == null) {
            try {
                save(global);
            } catch (IOException | RuntimeException e) {
                // 首次运行没有数据需要保护；下一次 save 会重试。
                LOGGER.error("首次运行时写入空配置失败，稍后重试。", e);
            }
        }
        return global;
    }

    private static JsonObject readConfig(Path file) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
        if (attrs.isSymbolicLink())
            attrs = Files.readAttributes(file, BasicFileAttributes.class);
        if (!attrs.isRegularFile())
            throw new IOException("配置路径不是普通文件: " + file);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            if (JsonParser.parseReader(reader) instanceof JsonObject config)
                return config;
            throw new IOException("配置根节点不是 JSON object: " + file);
        } catch (JsonParseException e) {
            // Gson 异常可能包含明文凭据。
            throw new IOException("配置 JSON 解析失败: " + file);
        }
    }

    private static BigDecimal configVersion(JsonObject config) {
        JsonElement version = config.get("version");
        if (version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()) {
            try {
                BigDecimal number = version.getAsBigDecimal();
                if (number.signum() >= 0 && number.stripTrailingZeros().scale() <= 0)
                    return number;
            } catch (NumberFormatException | ArithmeticException ignored) {
                // 不把版本字段原文写入日志。
            }
        }
        throw new IllegalStateException("配置文件缺少有效的整数版本");
    }

    private static void requireArray(JsonObject config, String field) {
        JsonElement value = config.get(field);
        if (value != null && !value.isJsonNull() && !value.isJsonArray())
            throw new IllegalStateException("配置字段必须是数组: " + field);
    }

    private static void mergeLegacy(ConfigRoot global, ConfigRoot imported) {
        global.accounts().addAll(imported.accounts());
        JsonArray servers = global.servers();
        for (JsonElement element : imported.servers()) {
            JsonObject incoming = element.getAsJsonObject();
            JsonObject existing = null;
            for (JsonElement known : servers) {
                if (known instanceof JsonObject server
                        && Objects.equals(server.get("type"), incoming.get("type"))
                        && Objects.equals(server.get("apiRoot"), incoming.get("apiRoot"))) {
                    existing = server;
                    break;
                }
            }
            if (existing == null) {
                servers.add(incoming);
            } else {
                for (var field : incoming.entrySet()) {
                    if (!existing.has(field.getKey()))
                        existing.add(field.getKey(), field.getValue());
                }
            }
        }
    }

    private ConfigRoot commitMigration(ConfigRoot root, Path payload, Path instance) {
        try {
            save(root);
        } catch (IOException | RuntimeException e) {
            degrade(e, false, "accountsx.config.degraded.title", "accountsx.config.degraded.desc");
            return root;
        }
        // 全局配置已经落盘。索引改名失败时保留载荷，避免留下无法重试的索引。
        if (instance != null) {
            try {
                Files.move(instance, instance.resolveSibling("accounts.json.migrated"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOGGER.warn("实例配置改名 .migrated 失败，保留旧载荷。", e);
                return root;
            }
        }
        if (payload != null) {
            try {
                Files.deleteIfExists(payload);
                Files.deleteIfExists(payload.getParent());
            } catch (DirectoryNotEmptyException ignored) {
                // 其他实例的载荷仍在使用。
            } catch (IOException e) {
                LOGGER.warn("清理已迁移载荷失败: {}", payload, e);
            }
        }
        return root;
    }

    /** 备份需要保护的原始文件并进入只读模式。 */
    private void degrade(Throwable cause, boolean backup, String titleKey, String descKey) {
        LOGGER.error("无法加载配置文件 {}，本次会话进入只读模式（保存被禁用，不会覆写用户数据）。", path, cause);
        if (backup && !readOnly && Files.isRegularFile(path)) {
            Path bak = null;
            try {
                String prefix = path.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_TS) + "-";
                bak = Files.createTempFile(path.toAbsolutePath().getParent(), prefix, "", permissions(PRIVATE_FILE));
                try (OutputStream out = Files.newOutputStream(bak, StandardOpenOption.WRITE)) {
                    Files.copy(path, out);
                }
                LOGGER.error("原配置已备份到 {}", bak);
            } catch (IOException | RuntimeException e) {
                if (bak != null)
                    deleteIncompleteFile(bak);
                LOGGER.error("备份配置文件失败，请勿修改或删除 {}。", path, e);
            }
        }
        readOnly = true;
        try {
            notice.show(titleKey, descKey);
        } catch (Throwable t) {
            // 无适配器环境（单测 / 极端启动早期）不能让提示失败升级成崩溃
            LOGGER.warn("展示配置降级 toast 失败", t);
        }
    }

    @Override
    public synchronized void save(ConfigRoot root) throws IOException {
        if (readOnly) {
            LOGGER.warn("配置存储处于只读降级模式，本次保存请求已丢弃（防止覆写用户数据）。");
            return;
        }

        prepareConfigDirectory();

        // 同目录随机临时名避开多实例冲突，明文凭据从创建起就只能由所有者读取。
        Path tmp = createPrivateTemp(path);
        try {
            Files.writeString(tmp, gson.toJson(root.raw()), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            fsync(tmp);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 极少数文件系统（某些网络挂载）不支持原子移动；非原子替换仍优于失败，
                // 且此刻 .tmp 已完整落盘，半写风险窗口只剩 move 本身。
                LOGGER.debug("文件系统不支持 ATOMIC_MOVE，退化为普通替换移动: {}", path, e);
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            deleteIncompleteFile(tmp);
            throw e;
        }
    }

    private static void deleteIncompleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("清理不完整的配置文件失败: {}", file, e);
        }
    }

    /** 在协调锁创建自己的目录之前，以私有权限创建缺失的配置父目录。 */
    public synchronized void prepareConfigDirectory() throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        if (!Files.isDirectory(dir))
            Files.createDirectories(dir, permissions(PRIVATE_DIRECTORY));
    }

    private Path createPrivateTemp(Path target) throws IOException {
        return Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName() + ".tmp-", "",
                permissions(PRIVATE_FILE));
    }

    private static void fsync(Path tmp) {
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        } catch (UnsupportedOperationException | IOException e) {
            // 内存文件系统（jimfs）与个别挂载点不支持 force；fsync 是尽力而为，不值得失败整个保存。
            LOGGER.debug("跳过 fsync: {}", tmp, e);
        }
    }

    private FileAttribute<?>[] permissions(FileAttribute<?> privatePermissions) {
        if (fs.supportedFileAttributeViews().contains("posix"))
            return new FileAttribute<?>[]{privatePermissions};
        if (!permissionsWarned) {
            permissionsWarned = true;
            LOGGER.warn("当前文件系统不支持 POSIX 权限；请避免分享配置文件 {}（含明文凭据）。", path);
        }
        return new FileAttribute<?>[0];
    }

    /** 启动时未取得配置锁，只读取当前格式，不备份、迁移或写入。 */
    public synchronized void disableWrites() {
        readOnly = true;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    /** 配置文件的解析结果路径（供多实例协调使用）。 */
    public Path path() {
        return path;
    }
}
