package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonObject;

import java.nio.file.Path;

/** 单跳配置转换。版本号由链维护，旧载荷只能在全局文件提交后清理。 */
public interface Migration {
    int fromVersion();

    int toVersion();

    Result apply(JsonObject config, MigrationContext ctx) throws Exception;

    record Result(JsonObject config, Path legacyPayload) {
        public Result(JsonObject config) {
            this(config, null);
        }

        @Override
        public String toString() {
            return "Migration.Result";
        }
    }
}
