package top.syshub.accountsx.common.storage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 全局配置存储（P2.2，决策 D3）。
 *
 * <p>取代旧的"实例配置 {@code <game>/config/accountsx/accounts.json} + 载荷
 * {@code ~/.accountsx/<id>.json}"双文件结构，收敛为单文件
 * {@code ~/.config/accountsx.json}（可被 {@code ACCOUNTSX_CONFIG} 环境变量覆盖）。</p>
 *
 * <p>契约：{@link #load()} 永不抛异常也永不返回 null；读取失败 / 迁移失败时进入
 * <b>只读降级模式</b>（{@link #isReadOnly()} 返回 true），后续 {@link #save} 记 warn
 * 并丢弃——这是对审计 1.8（"读失败被 initialize() 的无条件 save() 覆写成 []"）的结构性修复。
 * 旧 {@code ConfigHandle} 被 {@code AccountManager.initialize()} 末尾那个无条件
 * {@code save()} 击穿的教训，正是本接口把"禁写"放进实现内部而不是交给调用方自律的原因。</p>
 */
public interface ConfigStore {
    /** v4：单文件 + servers/accounts 分离（schema 定稿见 REFACTORING/08-config-schema.md 与 v4.json）。 */
    int CURRENT_VERSION = 4;

    /**
     * 读取并迁移到 {@link #CURRENT_VERSION}。
     * 文件不存在 = 正常首次运行：落盘一份空的当前版本配置并返回它。
     */
    ConfigRoot load();

    /** 启动时装载配置，必要时从指定目录导入旧实例配置；null 表示不导入。 */
    default ConfigRoot load(Path legacyGameConfigDir) {
        return load();
    }

    /** 原子写：{@code .tmp} → fsync → {@code ATOMIC_MOVE}。只读降级模式下丢弃并记 warn。 */
    void save(ConfigRoot root) throws IOException;

    /**
     * 保存一份已序列化好的快照（与 {@link #save} 同等原子语义）。
     * 给"锁内序列化、锁外落盘"的调用方用：快照文本在调用方锁内生成，
     * 落盘时不再触碰 live 对象，避免序列化与并发修改撕裂（P2.4-review 竞态项）。
     * 默认实现按 {@code save} 语义回放（只读降级同样丢弃）。
     */
    default void saveSnapshot(String json) throws IOException {
        save(new ConfigRoot(com.google.gson.JsonParser.parseString(json).getAsJsonObject()));
    }

    /** 本次会话是否处于只读降级模式（读失败 / 迁移失败 / 配置版本高于模组支持版本）。 */
    boolean isReadOnly();
}
