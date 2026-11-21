package top.syshub.accountsx.common.ui;

import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.offline.OfflineAccount;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标题屏那行「正在使用 …」的取词：外置账号的来源是服务器名，不是类型名。
 */
class TranslatorTest {

    /** 记录每次翻译的 key 与参数，用来断言最终取到哪一个 key。 */
    private static final class RecordingHandle implements Translator.Handle<String> {
        private final List<String> keys = new ArrayList<>();
        private final List<String[]> args = new ArrayList<>();

        @Override
        public String translate(String key, String... values) {
            keys.add(key);
            args.add(values);
            return key;
        }
    }

    private static OfflineAccount account() {
        return new OfflineAccount("fake-token", "Steve", UUID.randomUUID());
    }

    @Test
    void withoutASourceName_itFallsBackToTheTypeName() {
        RecordingHandle handle = new RecordingHandle();

        new Translator<>(handle).translate(account(), null);

        assertThat(handle.keys).containsExactly("accountsx.account.type.offline.using");
        assertThat(handle.args.get(0)).containsExactly("Steve");
    }

    @Test
    void blankSourceNameIsTreatedAsMissing() {
        RecordingHandle handle = new RecordingHandle();

        new Translator<>(handle).translate(account(), "   ");

        assertThat(handle.keys).containsExactly("accountsx.account.type.offline.using");
    }

    @Test
    void aKnownSourceNameBecomesTheSubjectOfTheLine() {
        RecordingHandle handle = new RecordingHandle();

        new Translator<>(handle).translate(account(), "littleskin.cn");

        assertThat(handle.keys).containsExactly("accountsx.account.type.offline.using_server");
        assertThat(handle.args.get(0)).containsExactly("littleskin.cn", "Steve");
    }

    @Test
    void singleArgumentOverloadKeepsThePlainTypeLine() {
        RecordingHandle handle = new RecordingHandle();

        new Translator<>(handle).translate(account());

        assertThat(handle.keys).containsExactly("accountsx.account.type.offline.using");
    }
}
