package top.syshub.accountsx.core.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.core.accounts.model.AccountState;
import top.syshub.accountsx.core.accounts.model.AccountType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 现状快照测试：i18n key 覆盖率。
 * 验证每个 {@link AccountType} 和 {@link AccountState} 枚举值
 * 在 en_us.json 和 zh_cn.json 中都有对应的翻译 key。
 *
 * <p>翻译 key 格式（由 {@link Translator} 拼接 configId 生成）：
 * <ul>
 *   <li>{@code accountsx.account.type.<configId>.name}</li>
 *   <li>{@code accountsx.account.type.<configId>.using}</li>
 *   <li>{@code accountsx.account.state.<STATE>.name}</li>
 * </ul>
 */
class I18nKeyCoverageTest {

    private static final Gson GSON = new Gson();

    @Test
    void enUs_hasAllTypeKeys() throws IOException {
        Map<String, String> lang = loadLang("en_us.json");
        for (AccountType type : AccountType.VALUES) {
            if (type == AccountType.ENV_DEFAULT) continue; // ENV_DEFAULT 不需要 i18n
            String configId = type.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(lang).containsKey("accountsx.account.type." + configId + ".name");
        }
    }

    @Test
    void enUs_hasAllTypeUsingKeys() throws IOException {
        Map<String, String> lang = loadLang("en_us.json");
        for (AccountType type : AccountType.VALUES) {
            if (type == AccountType.ENV_DEFAULT) continue;
            String configId = type.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(lang).containsKey("accountsx.account.type." + configId + ".using");
        }
    }

    @Test
    void enUs_hasAllStateKeys() throws IOException {
        Map<String, String> lang = loadLang("en_us.json");
        for (AccountState state : AccountState.values()) {
            String stateId = state.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(lang).containsKey("accountsx.account.state." + stateId + ".name");
        }
    }

    /**
     * en/zh key 数量一致，防止漏翻译
     */
    @Test
    void zhCn_keyCountMatchesEnUs() throws IOException {
        Map<String, String> en = loadLang("en_us.json");
        Map<String, String> zh = loadLang("zh_cn.json");
        assertThat(zh.keySet()).isEqualTo(en.keySet());
    }

    /**
     * zh_cn.json 也包含所有 Type key
     */
    @Test
    void zhCn_hasAllTypeKeys() throws IOException {
        Map<String, String> lang = loadLang("zh_cn.json");
        for (AccountType type : AccountType.VALUES) {
            if (type == AccountType.ENV_DEFAULT) continue;
            String configId = type.name().toLowerCase(java.util.Locale.ROOT);
            assertThat(lang).containsKey("accountsx.account.type." + configId + ".name");
            assertThat(lang).containsKey("accountsx.account.type." + configId + ".using");
        }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    private static Map<String, String> loadLang(String filename) throws IOException {
        try (InputStream is = I18nKeyCoverageTest.class.getResourceAsStream(
                "/assets/accountsx/lang/" + filename)) {
            assertThat(is).as("Resource %s not found", filename).isNotNull();
            JsonObject json = GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonObject.class);
            var result = new java.util.HashMap<String, String>();
            for (var entry : json.entrySet()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
            return java.util.Map.copyOf(result);
        }
    }
}
