package top.syshub.accountsx.core.manager.config;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.core.utils.NetworkUtils;

import java.util.UUID;

public enum ConfigVersion {
    BASE(0) {
        @Override
        protected void upgrade(JsonObject config) {
            throw new IllegalStateException("There's no more legacy version.");
        }
    }, INJECTOR_SAFETY(1) {
        @Override
        protected void upgrade(JsonObject config) {
            if (config.get("accounts") instanceof JsonArray accounts) {
                for (int i = accounts.size() - 1; i >= 0; i--) {
                    JsonElement account = accounts.get(i);
                    if (account instanceof JsonObject jo && jo.get("type") instanceof JsonPrimitive jp && jp.isString()) {
                        if ("INJECTOR".equals(jp.getAsString())) {
                            accounts.remove(i);
                        }
                    }
                }
            }
        }
    }, RENAME_ACCOUNT_TYPE(2) {
        @Override
        protected void upgrade(JsonObject config) {
            if (config.get("accounts") instanceof JsonArray accounts) {
                // 倒序遍历以便安全移除；未知类型跳过（移除）+ 警告，不再抛异常导致整个账号集加载失败（1.9 / P1.1）
                for (int i = accounts.size() - 1; i >= 0; i--) {
                    JsonElement account = accounts.get(i);
                    if (account instanceof JsonObject jo && jo.get("type") instanceof JsonPrimitive jp && jp.isString()) {
                        String renamed = switch (jp.getAsString()) {
                            case "OFFLINE" -> "offline";
                            case "MICROSOFT" -> "microsoft";
                            case "INJECTOR" -> "injector.authlib-injector";
                            default -> null;
                        };
                        if (renamed != null) {
                            jo.addProperty("type", renamed);
                        } else {
                            LOGGER.warn("迁移时遇到未知账号类型 '{}'，已跳过该账号。", jp.getAsString());
                            accounts.remove(i);
                        }
                    }
                }
            }
        }
    }, SECURITY_STORAGE(3) {
        @Override
        protected void upgrade(JsonObject config) {
            String id = UUID.randomUUID().toString();
            config.addProperty("id", id);

            if (config.get("accounts") instanceof JsonArray accounts)
                ConfigHandle.writeAccounts(id, NetworkUtils.GSON.toJson(accounts));

            config.remove("accounts");
        }
    };

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigVersion.class);

    public static final ConfigVersion[] VALUES = values();

    private final int version;

    ConfigVersion(int version) {
        this.version = version;
    }

    public int getVersion() {
        return version;
    }

    protected abstract void upgrade(JsonObject config);
}
