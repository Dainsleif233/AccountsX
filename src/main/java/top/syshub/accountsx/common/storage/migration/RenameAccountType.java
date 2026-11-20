package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1→2 {@code RENAME_ACCOUNT_TYPE}：大写枚举名改持久化 configId
 * （{@code OFFLINE→offline} 等）。未知类型跳过 + 警告并移除，不中止整条链
 * （P1.1 修的行为，旧链同样适用）。
 */
public final class RenameAccountType implements Migration {
    private static final Logger LOGGER = LoggerFactory.getLogger(RenameAccountType.class);

    @Override
    public int fromVersion() {
        return 1;
    }

    @Override
    public int toVersion() {
        return 2;
    }

    static String rename(String type) {
        return switch (type) {
            case "OFFLINE", "offline" -> "offline";
            case "MICROSOFT", "microsoft" -> "microsoft";
            case "INJECTOR", "injector.authlib-injector" -> "injector.authlib-injector";
            default -> null;
        };
    }

    @Override
    public Result apply(JsonObject config, MigrationContext ctx) {
        if (config.get("accounts") instanceof JsonArray accounts) {
            // 倒序遍历以便安全移除
            for (int i = accounts.size() - 1; i >= 0; i--) {
                JsonElement account = accounts.get(i);
                if (account instanceof JsonObject jo
                        && jo.get("type") instanceof JsonPrimitive jp && jp.isString()) {
                    String renamed = rename(jp.getAsString());
                    if (renamed != null) {
                        jo.addProperty("type", renamed);
                    } else {
                        LOGGER.warn("迁移时遇到未知账号类型，已跳过该账号。");
                        accounts.remove(i);
                    }
                }
            }
        }
        return new Result(config);
    }
}
