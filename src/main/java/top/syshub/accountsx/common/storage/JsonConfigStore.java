package top.syshub.accountsx.common.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.storage.migration.MigrationChain;
import top.syshub.accountsx.common.storage.migration.MigrationContext;
import top.syshub.accountsx.common.utils.NetworkUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link ConfigStore} 的 JSON 文件实现（P2.2）。
 *
 * <p>文件系统、路径、环境变量、迁移链与提示回调全部注入，可在 jimfs 内存文件系统中
 * 完整测试（含降级路径）；生产入口是 {@link #createDefault()}。</p>
 *
 * <p>写路径：{@code .tmp} → fsync → {@code ATOMIC_MOVE}（REPLACE_EXISTING），
 * 崩溃或断电不会留下半截配置文件。POSIX 下尽力把文件收紧到 600、目录 700
 * （文件含明文 token），Windows 上只记一次 warn。</p>
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
     * 迁移链由调用方传入（生产用 {@code StandardMigrations.chain(NetworkUtils.GSON)}，P2.4 接线；
     * 传 {@link MigrationChain#EMPTY} 时遇到旧版本配置会安全降级而不是覆写）。
     */
    public static JsonConfigStore createDefault(MigrationChain migrations) {
        FileSystem fs = FileSystems.getDefault();
        Path path = resolvePath(System::getenv, System.getProperty("user.home"), fs);
        Path gameConfigDir = FabricLoader.getInstance().getConfigDir();
        MigrationContext ctx = new MigrationContext() {
            @Override
            public FileSystem fs() {
                return fs;
            }

            @Override
            public Path legacyGameConfigDir() {
                return gameConfigDir.resolve("accountsx");
            }

            @Override
            public Path legacyHomeDir() {
                return fs.getPath(System.getProperty("user.home"), ".accountsx");
            }

            @Override
            public Path globalConfigFile() {
                return path;
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
        // 区分"文件不存在"（正常首次运行，落盘空配置）
        // 与"文件存在但读失败"（异常，进降级）——审计 1.8 的覆写事故正是把两者混为一谈。
        if (!Files.exists(path)) {
            ConfigRoot fresh = ConfigRoot.empty(CURRENT_VERSION);
            try {
                save(fresh);
            } catch (IOException e) {
                // 首次运行的空配置写失败不致命：下一次 save 会重试，也不进只读降级（没有数据可保护）
                LOGGER.error("首次运行时写入空配置失败，稍后重试。", e);
            }
            return fresh;
        }

        JsonObject raw;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // 裸 JsonParser：gson 实例带的 UUIDTypeAdapter 等会污染 JsonElement 目标（P2.4 实测回归）
            JsonElement parsed = com.google.gson.JsonParser.parseReader(reader);
            if (!(parsed instanceof JsonObject jo))
                throw new IllegalStateException("配置根节点不是 JSON object");
            raw = jo;
        } catch (IOException | RuntimeException e) {
            degrade(e, true, "accountsx.config.degraded.title", "accountsx.config.degraded.desc");
            return ConfigRoot.empty(CURRENT_VERSION);
        }

        JsonElement version = raw.get("version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
            LOGGER.error("配置文件缺少合法 version 字段: {}", path);
            degrade(new IllegalStateException("missing version"), true,
                    "accountsx.config.degraded.title", "accountsx.config.degraded.desc");
            return ConfigRoot.empty(CURRENT_VERSION);
        }

        int fileVersion = version.getAsInt();
        if (fileVersion > CURRENT_VERSION) {
            // 用户降级了模组：不迁移、不覆盖（覆写会静默丢新字段），文件本身没坏，不必备份。
            degrade(new IllegalStateException("config version " + fileVersion + " > " + CURRENT_VERSION), false,
                    "accountsx.config.tooNew.title", "accountsx.config.tooNew.desc");
            return ConfigRoot.empty(CURRENT_VERSION);
        }

        if (fileVersion < CURRENT_VERSION) {
            try {
                raw = migrations.upgrade(raw, fileVersion, migrationContext);
            } catch (Exception e) {
                degrade(e, true, "accountsx.config.degraded.title", "accountsx.config.degraded.desc");
                return ConfigRoot.empty(CURRENT_VERSION);
            }
            // 迁移成功立刻回写（P2.3：不再依赖调用方 save() 自愈——旧实现靠
            // initialize() 末尾的无条件 save()，中途崩溃会重复迁移产生残留）。
            // 回写失败不致命：内存里已是新 schema，下次启动重跑迁移（各跳幂等）。
            ConfigRoot upgraded = new ConfigRoot(raw);
            try {
                save(upgraded);
            } catch (IOException e) {
                LOGGER.error("迁移结果回写失败，下次启动将重试迁移。", e);
            }
            return upgraded;
        }
        return new ConfigRoot(raw);
    }

    /**
     * 备份（仅文件未损坏且需要保护的场景）+ ERROR + toast + 置只读降级标志。
     * 备份时间戳同秒冲突时追加序号，绝不覆盖已有备份。
     */
    private void degrade(Throwable cause, boolean backup, String titleKey, String descKey) {
        LOGGER.error("无法加载配置文件 {}，本次会话进入只读模式（保存被禁用，不会覆写用户数据）。", path, cause);
        if (backup) {
            try {
                Path bak = path.resolveSibling(path.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_TS));
                int suffix = 1;
                while (Files.exists(bak)) {
                    bak = path.resolveSibling(path.getFileName() + ".bak-" + LocalDateTime.now().format(BACKUP_TS) + "-" + suffix++);
                }
                Files.copy(path, bak);
                LOGGER.error("原配置已备份到 {}", bak);
            } catch (IOException e) {
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
    public void save(ConfigRoot root) throws IOException {
        if (readOnly) {
            LOGGER.warn("配置存储处于只读降级模式，本次保存请求已丢弃（防止覆写用户数据）。");
            return;
        }

        Path dir = path.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
            tightenPermissions(dir, "rwx------");
        }

        // 临时名带进程唯一后缀：多实例同时保存时不会互相截断同一个 .tmp，
        // 也不会抢同一个 tmp 的 move（全局写锁是另一重保险，这里保证即使锁失效也不损坏数据）。
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp-" + UniqueSuffix.next());
        try {
            Files.writeString(tmp, gson.toJson(root.raw()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
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
            // 失败路径清理 .tmp：残留的半截 tmp 会让下次 save 的 TRUNCATE_EXISTING 掩盖问题，
            // 也避免用户目录里积累垃圾文件。清理本身失败不升级异常。
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                LOGGER.debug("清理写入失败的临时文件失败: {}", tmp, ignored);
            }
            throw e;
        }
        tightenPermissions(path, "rw-------");
    }

    /** 进程内单调递增的唯一后缀，供临时文件名使用（跨进程由 pid 保证不重）。 */
    private static final class UniqueSuffix {
        private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

        private UniqueSuffix() {
        }

        static String next() {
            return ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet();
        }
    }

    private static void fsync(Path tmp) {
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        } catch (UnsupportedOperationException | IOException e) {
            // 内存文件系统（jimfs）与个别挂载点不支持 force；fsync 是尽力而为，不值得失败整个保存。
            LOGGER.debug("跳过 fsync: {}", tmp, e);
        }
    }

    /** 含明文 token 的文件尽力收紧权限；非 POSIX（Windows）上只记一次 warn。 */
    private void tightenPermissions(Path target, String spec) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString(spec);
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException e) {
            if (!permissionsWarned) {
                permissionsWarned = true;
                LOGGER.warn("当前文件系统不支持 POSIX 权限，无法收紧 {} 的文件权限；请避免分享该文件（含明文凭据）。", target);
            }
        } catch (IOException e) {
            LOGGER.debug("设置权限失败: {}", target, e);
        }
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    /** 配置文件的解析结果路径（诊断用；不含用户数据）。触发迁移逻辑用它定位全局文件。 */
    public Path path() {
        return path;
    }

    /** 迁移执行环境（P2.4 触发旧实例迁移时复用同一份路径配置）。 */
    public MigrationContext migrationContext() {
        return migrationContext;
    }
}
