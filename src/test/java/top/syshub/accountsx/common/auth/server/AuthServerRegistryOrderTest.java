package top.syshub.accountsx.common.auth.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.net.HttpGateway;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务器顺序也是共享状态：移位要真的改顺序、边界要钳住、本地刚移过的顺序不能被对端旧快照盖回去。
 * 与账号顺序同一套 orderVersion/persistedOrderVersion 机制，这里直接验注册表层。
 */
class AuthServerRegistryOrderTest {

    private static JsonObject server(String apiRoot, String name) {
        JsonObject jo = new JsonObject();
        jo.addProperty("type", "yggdrasil");
        jo.addProperty("apiRoot", apiRoot);
        jo.addProperty("displayName", name);
        jo.addProperty("cachedAt", 1L);
        return jo;
    }

    private static JsonArray array(JsonObject... entries) {
        JsonArray a = new JsonArray();
        for (JsonObject e : entries) {
            a.add(e);
        }
        return a;
    }

    private static AuthServerRegistry registry(JsonArray disk, AtomicInteger changes) {
        // 这些路径（merge/move/persist）不碰网络，网关只需非 null。
        AuthServerRegistry registry = new AuthServerRegistry(new NoopGateway(), changes::incrementAndGet);
        registry.mergeFromDisk(disk, Set.of());
        return registry;
    }

    /** 顺序逻辑不发起任何请求；真被调到说明测错了路径，直接抛。 */
    private static final class NoopGateway implements HttpGateway {
        @Override
        public JsonObject get(String url) throws IOException {
            throw new UnsupportedOperationException(url);
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) throws IOException {
            throw new UnsupportedOperationException(url);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            throw new UnsupportedOperationException(url);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException(url);
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException(url);
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException(url);
        }

        @Override
        public Headers getHeaders(String url) {
            throw new UnsupportedOperationException(url);
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException(url);
        }
    }

    private static List<String> order(AuthServerRegistry registry) {
        return registry.servers().stream().map(AuthServer::apiRoot).toList();
    }

    @Test
    void move_reordersAndClampsAtBoundaries() {
        AuthServerRegistry registry = registry(array(
                server("https://a.example", "A"),
                server("https://b.example", "B"),
                server("https://c.example", "C")), new AtomicInteger());

        assertThat(registry.move(AuthServerKind.YGGDRASIL, "https://c.example", -1)).isTrue();
        assertThat(order(registry)).containsExactly("https://a.example", "https://c.example", "https://b.example");

        assertThat(registry.move(AuthServerKind.YGGDRASIL, "https://a.example", -1)).isFalse();
        assertThat(order(registry)).containsExactly("https://a.example", "https://c.example", "https://b.example");

        assertThat(registry.move(AuthServerKind.YGGDRASIL, "https://b.example", 1)).isFalse();
        assertThat(registry.move(AuthServerKind.YGGDRASIL, "https://missing.example", -1)).isFalse();
    }

    @Test
    void move_marksDirtyAndBumpsOrderVersion() {
        AtomicInteger changes = new AtomicInteger();
        AuthServerRegistry registry = registry(array(
                server("https://a.example", "A"),
                server("https://b.example", "B")), changes);
        long before = registry.orderVersion();
        int dirtyBefore = changes.get();

        assertThat(registry.move(AuthServerKind.YGGDRASIL, "https://b.example", -1)).isTrue();

        assertThat(registry.orderVersion()).isGreaterThan(before);
        assertThat(changes.get()).isGreaterThan(dirtyBefore);
    }

    @Test
    void merge_adoptsDiskOrderWhenNoLocalMove() {
        AuthServerRegistry registry = registry(array(
                server("https://a.example", "A"),
                server("https://b.example", "B")), new AtomicInteger());
        registry.markOrderPersisted(registry.orderVersion());

        // 对端把顺序移成 [B, A] 并落盘；本地没有未落盘的移位，应采纳。
        registry.mergeFromDisk(array(
                server("https://b.example", "B"),
                server("https://a.example", "A")), Set.of());

        assertThat(order(registry)).containsExactly("https://b.example", "https://a.example");
    }

    @Test
    void merge_keepsLocalOrderAfterAnUnpersistedMove() {
        AuthServerRegistry registry = registry(array(
                server("https://a.example", "A"),
                server("https://b.example", "B")), new AtomicInteger());
        registry.markOrderPersisted(registry.orderVersion());

        // 本地把 B 移到 A 前（尚未落盘）。
        registry.move(AuthServerKind.YGGDRASIL, "https://b.example", -1);
        assertThat(order(registry)).containsExactly("https://b.example", "https://a.example");

        // 对端旧快照仍是 [A, B]：本地刚移过，不能被它盖回去。
        registry.mergeFromDisk(array(
                server("https://a.example", "A"),
                server("https://b.example", "B")), Set.of());

        assertThat(order(registry)).containsExactly("https://b.example", "https://a.example");
    }

    @Test
    void persist_writesCurrentOrder() {
        AuthServerRegistry registry = registry(array(
                server("https://a.example", "A"),
                server("https://b.example", "B")), new AtomicInteger());
        registry.move(AuthServerKind.YGGDRASIL, "https://b.example", -1);

        JsonArray out = new JsonArray();
        registry.persist(out);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getAsJsonObject().get("apiRoot").getAsString()).isEqualTo("https://b.example");
        assertThat(out.get(1).getAsJsonObject().get("apiRoot").getAsString()).isEqualTo("https://a.example");
    }
}
