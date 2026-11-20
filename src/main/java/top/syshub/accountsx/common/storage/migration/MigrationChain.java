package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.storage.ConfigStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按配置版本应用迁移，缺少所需的跳时交给存储层降级。 */
public final class MigrationChain {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationChain.class);
    public static final MigrationChain EMPTY = new MigrationChain(List.of());

    private final Map<Integer, Migration> steps;

    public MigrationChain(List<Migration> steps) {
        Map<Integer, Migration> byVersion = new HashMap<>();
        for (Migration step : steps) {
            if (step.toVersion() <= step.fromVersion() || step.toVersion() > ConfigStore.CURRENT_VERSION)
                throw new IllegalArgumentException("迁移版本必须递增到受支持的版本: "
                        + step.fromVersion() + " -> " + step.toVersion());
            if (byVersion.putIfAbsent(step.fromVersion(), step) != null)
                throw new IllegalArgumentException("同一版本注册了多个迁移: " + step.fromVersion());
        }
        this.steps = Map.copyOf(byVersion);
    }

    public Migration.Result upgrade(JsonObject raw, int version, MigrationContext ctx) throws Exception {
        if (version < 0 || version > ConfigStore.CURRENT_VERSION)
            throw new IllegalStateException("配置版本不受支持: " + version);

        Migration.Result result = new Migration.Result(raw);
        while (version < ConfigStore.CURRENT_VERSION) {
            Migration step = steps.get(version);
            if (step == null)
                throw new IllegalStateException("缺少 " + version + " 的下一跳迁移");
            LOGGER.info("执行配置迁移 {} -> {}", version, step.toVersion());
            result = step.apply(result.config(), ctx);
            version = step.toVersion();
        }
        result.config().addProperty("version", version);
        return result;
    }
}
