package top.syshub.accountsx.common.manager.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.utils.NetworkUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConfigHandle {
    private ConfigHandle() {}

    /**
     * 与 {@code AccountsX.LOGGER} 同名（{@code accountsx}），但**不**引用那个类。
     *
     * <p>{@code AccountsX} 实现了 Fabric 的 {@code ClientModInitializer}，加载它会要求 fabric-loader
     * 在类路径上。只读降级恰恰必须能在「没有 Fabric 运行时」的环境里生效（例如单元测试），否则
     * 降级例程自己抛 {@code NoClassDefFoundError}，反而把要报告的读失败盖掉。日志名保持一致，
     * 生产环境输出行为与 {@code AccountsX.LOGGER} 完全相同。</p>
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("accountsx");

    private static String id;

    /**
     * 只读降级标志：为 true 时 {@link #write()} 直接放弃写盘。
     *
     * <p>不变量（AGENTS.md）：载荷读失败时**不得**写回配置。原实现里 {@code load()} 把任何
     * {@code Throwable} 都吞成空列表，而 {@code id} 早已赋值、{@code AccountManager.initialize()}
     * 又无条件 {@code save()}，于是 {@code ~/.accountsx/<id>.json} 会被覆写成 {@code []}——
     * 一次瞬时 I/O 错误，或**一个**账号带未知 {@code type}（{@code AccountTypeAdapter.read}
     * 抛 IOException 会让整个列表反序列化失败），就会静默删除用户全部账号。</p>
     *
     * <p>降级后本次会话的账号列表可能不完整，但磁盘数据保持原样，用户下次启动仍有完整账号。</p>
     */
    private static volatile boolean readOnly;

    private static final String CONFIG_LOCATION = "accountsx/accounts.json";

    /** 进入只读降级：本次会话不再写盘，避免用读到的（可能为空的）状态覆盖用户数据。 */
    static void enterReadOnlyMode(String reason) {
        if (!readOnly) {
            readOnly = true;
            LOGGER.error("AccountsX 账户数据读取失败，已进入只读降级模式，本次会话不会写回配置（{}）。", reason);
        }
    }

    /** 供测试与诊断：当前是否处于只读降级模式。 */
    public static boolean isReadOnly() {
        return readOnly;
    }

    /**
     * 实例配置的序列化载体：仅含 version 与 id，由 Gson 通过 record 访问器反射读写。
     * 账号载荷本身另存于 {@code ~/.accountsx/<id>.json}，不在此处。
     */
    private record Config(int version, String id) {
        static final int CURRENT_VERSION = ConfigVersion.VALUES[ConfigVersion.VALUES.length - 1].getVersion();
    }

    /**
     * 写入一份默认实例配置（version + id）并落盘空的账号载荷。
     * 配置文件缺失或被非法占用（非普通文件）时调用，副作用与 {@link #write()} 保持一致。
     */
    private static void createDefaultConfig(Path configFile) throws IOException {
        if (id == null)
            id = UUID.randomUUID().toString();
        writeAccounts(id, NetworkUtils.GSON.toJson(List.of()));
        Files.writeString(configFile, NetworkUtils.GSON.toJson(new Config(Config.CURRENT_VERSION, id)));
    }

    public static List<? extends BaseAccount> load() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_LOCATION);

        try {
            if (!Files.exists(configFile)) {
                Files.createDirectories(configFile.getParent());
                createDefaultConfig(configFile);
                return List.of();
            }

            if (!Files.isRegularFile(configFile)) {
                Files.delete(configFile);
                createDefaultConfig(configFile);
                return List.of();
            }

            JsonElement data;
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                data = NetworkUtils.GSON.fromJson(reader, JsonElement.class);
            }

            if (data instanceof JsonObject jo) {
                if (jo.get("version") instanceof JsonPrimitive versionJP && versionJP.isNumber()) {
                    int configVersion = versionJP.getAsNumber().intValue();

                    for (ConfigVersion value : ConfigVersion.VALUES) {
                        if (configVersion < value.getVersion()) {
                            value.upgrade(jo);
                        }
                    }

                    String parsedId = jo.get("id").getAsString();
                    try {
                        UUID.fromString(parsedId);
                    } catch (Exception e) {
                        // id 非法意味着无法定位载荷文件：这属于读失败，不能当作「没有账号」处理。
                        throw new IllegalStateException("Illegal account payload id: " + parsedId, e);
                    }

                    // 先完整读出载荷，成功后才把 id 落地 —— 否则读失败后 id 仍然有效，
                    // 紧接着的 save() 就会把该 id 对应的载荷文件覆写成空列表。
                    // 注意必须把 parsedId 作为参数传进去：静态字段此刻仍是 null，
                    // 让 getAccounts() 读静态字段会去找 ~/.accountsx/null.json，
                    // 每次冷启动都误判为读失败并进入只读降级（账号永远加载不出来）。
                    List<? extends BaseAccount> loaded = getAccounts(true, parsedId);
                    id = parsedId;
                    return loaded;
                }
            }

            throw new IllegalStateException("Illegal config.");
        } catch (Throwable t) {
            // 只读降级：宁可不写，也不能用读失败得到的空状态覆盖用户数据。
            LOGGER.warn("Cannot load the config file.", t);
            enterReadOnlyMode(String.valueOf(t));
            return List.of();
        }
    }

    public static void write() throws IOException {
        if (readOnly) {
            // 读失败后禁止写回：否则会用不完整/空的账号列表覆盖用户数据（见 readOnly 的注释）。
            LOGGER.warn("AccountsX is in read-only mode; skipping the config write.");
            return;
        }

        Path configFile = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_LOCATION);

        List<BaseAccount> accounts = new ArrayList<>();

        for (BaseAccount account : AccountManager.getAccountsView()) {
            if (account.getAccountType() != AccountType.ENV_DEFAULT) {
                accounts.add(account);
            }
        }

        if (id == null)
            id = UUID.randomUUID().toString();
        writeAccounts(id, NetworkUtils.GSON.toJson(accounts));

        try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
            NetworkUtils.GSON.toJson(new Config(Config.CURRENT_VERSION, id), writer);
        }
    }

    /**
     * 读取账号载荷。
     *
     * @param configExisted 配置文件是否本来就存在（id 来自磁盘）。为 true 时「看不到载荷文件」
     *                      属于读失败（进入只读降级），而不是「这个实例还没有账号」。
     * @param payloadId     载荷文件名用的 id。冷启动路径上静态字段 {@code id} 尚未落地，
     *                      必须由调用方显式传入，否则会拼出 {@code null.json} 这种假路径。
     */
    private static List<? extends BaseAccount> getAccounts(boolean configExisted, String payloadId) {
        String userHome = System.getProperty("user.home");
        Path accountsFile = Path.of(userHome, ".accountsx", payloadId + ".json");

        try {
            if (!Files.exists(accountsFile) || !Files.isRegularFile(accountsFile)) {
                // 配置文件已存在（id 来自磁盘）却看不到载荷文件：这是「读不到」，不是「没有账号」。
                // 若当成空账号集放行，紧接着的 save() 会新建一个 [] 覆盖掉（可能是尚未同步完成的）
                // 真实数据；进入只读降级则只影响本次会话。
                if (configExisted) {
                    enterReadOnlyMode("accounts payload is missing or not a regular file: " + accountsFile);
                }
                return List.of();
            }
            try (Reader reader = Files.newBufferedReader(accountsFile, StandardCharsets.UTF_8)) {
                List<? extends BaseAccount> accounts = NetworkUtils.GSON.fromJson(
                        reader,
                        new TypeToken<List<? extends BaseAccount>>() {}.getType()
                );
                if (accounts == null) {
                    // 空文件 / JSON null：解析成功但没有内容，仍按「无账号」处理。
                    return List.of();
                }
                return accounts;
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load accounts file", e);
        }
    }

    /**
     * 原子写入账号载荷：先写同目录临时文件，再 {@code ATOMIC_MOVE} 覆盖。
     *
     * <p>原来直接 {@code Files.writeString} 是就地截断：中途崩溃/断电/磁盘写满都会留下**残缺**的
     * 载荷文件，下次启动就是读失败 —— 结合只读降级虽不会再写坏，但用户账号已经不可读且没有备份。
     * 原子替换保证任何时刻目标文件要么是旧的完整内容、要么是新的完整内容。</p>
     */
    static void writeAccounts(String id, String accountString) {
        String userHome = System.getProperty("user.home");
        Path accountsFile = Path.of(userHome, ".accountsx", id + ".json");
        Path temporaryFile = accountsFile.resolveSibling(id + ".json.tmp");

        try {
            Files.createDirectories(accountsFile.getParent());
            Files.writeString(temporaryFile, accountString, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, accountsFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 临时文件与目标不同文件系统（例如 ~/.accountsx 是指向别处的软链接）时退回普通替换。
                Files.move(temporaryFile, accountsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to write accounts file", e);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException ignored) {
                // 临时文件残留无害：下次写入会复用同名文件并覆盖。
            }
        }
    }
}
