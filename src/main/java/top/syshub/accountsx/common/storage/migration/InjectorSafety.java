package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 0→1 {@code INJECTOR_SAFETY}：删除历史安全问题的 {@code type == "INJECTOR"} 账号
 * （旧 {@code ConfigVersion.INJECTOR_SAFETY} 的等价迁移，语义不变）。
 */
public final class InjectorSafety implements Migration {
    @Override
    public int fromVersion() {
        return 0;
    }

    @Override
    public int toVersion() {
        return 1;
    }

    @Override
    public Result apply(JsonObject config, MigrationContext ctx) {
        if (config.get("accounts") instanceof JsonArray accounts) {
            for (int i = accounts.size() - 1; i >= 0; i--) {
                JsonElement account = accounts.get(i);
                if (account instanceof JsonObject jo
                        && jo.get("type") instanceof JsonPrimitive jp
                        && jp.isString()
                        && "INJECTOR".equals(jp.getAsString())) {
                    accounts.remove(i);
                }
            }
        }
        return new Result(config);
    }
}
