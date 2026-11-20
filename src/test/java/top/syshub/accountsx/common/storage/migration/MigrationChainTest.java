package top.syshub.accountsx.common.storage.migration;

import com.google.gson.JsonObject;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.storage.ConfigStore;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2.2：迁移链接口骨架的测试。具体迁移实现（0→1 … 3→4）在 P2.3 落地并各自有跳测试；
 * 本类锁住链本身的推进 / 回写 / 防死循环语义。
 */
class MigrationChainTest {

    private final FileSystem fs = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build());
    private final MigrationContext ctx = new MigrationContext() {
        @Override
        public Path legacyHomeDir() {
            return fs.getPath("/home/.accountsx");
        }

        @Override
        public Path avatarCacheDir() {
            return fs.getPath("/home/.cache/accountsx/avatars");
        }
    };

    private static Migration hop(int from, int to, List<Integer> appliedOrder) {
        return new Migration() {
            @Override
            public int fromVersion() {
                return from;
            }

            @Override
            public int toVersion() {
                return to;
            }

            @Override
            public Result apply(JsonObject config, MigrationContext c) {
                appliedOrder.add(from);
                // 模拟上一跳产物对下一跳可见
                config.addProperty("seen_" + from, true);
                return new Result(config);
            }
        };
    }

    @Test
    void upgrade_walksFullChainAndStampsCurrentVersion() throws Exception {
        List<Integer> order = new ArrayList<>();
        MigrationChain chain = new MigrationChain(List.of(
                hop(0, 1, order), hop(1, 2, order), hop(2, 4, order)));
        JsonObject raw = new JsonObject();
        raw.addProperty("version", 0);

        JsonObject out = chain.upgrade(raw, 0, ctx).config();

        assertThat(order).containsExactly(0, 1, 2);
        assertThat(out.get("version").getAsInt()).isEqualTo(ConfigStore.CURRENT_VERSION);
        assertThat(out.get("seen_1").getAsBoolean()).isTrue(); // 链式推进，后一跳看得到前一跳结果
    }

    @Test
    void upgrade_missingHop_throws() {
        MigrationChain chain = new MigrationChain(List.of(hop(0, 1, new ArrayList<>())));
        JsonObject raw = new JsonObject();
        raw.addProperty("version", 0);

        assertThatThrownBy(() -> chain.upgrade(raw, 0, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 1 的下一跳迁移");
    }

    @Test
    void upgrade_fromNewerThanCurrent_throws() {
        MigrationChain chain = new MigrationChain(List.of());
        assertThatThrownBy(() -> chain.upgrade(new JsonObject(), ConfigStore.CURRENT_VERSION + 1, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不受支持");
    }

    @Test
    void constructor_rejectsNonMonotonicRegistration() {
        assertThatThrownBy(() -> new MigrationChain(List.of(hop(2, 1, new ArrayList<>()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("递增");
    }
}