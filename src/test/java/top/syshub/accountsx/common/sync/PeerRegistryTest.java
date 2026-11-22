package top.syshub.accountsx.common.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存活实例登记表决定「删账号时要不要多问一句」。判活靠 pid + 启动时刻：
 * pid 会被复用，启动时刻不符就需要当作死实例清掉，否则会误判别人还在用某个账号。
 */
class PeerRegistryTest {

    @TempDir
    Path dir;

    private SyncPaths paths;
    private PeerRegistry registry;
    private long livePid;
    private long liveStart;

    private void setUp() {
        paths = new SyncPaths(dir.resolve("accountsx.json"));
        ProcessHandle self = ProcessHandle.current();
        livePid = self.pid();
        liveStart = InstanceIdentity.startedAt(self).orElse(0L);
        InstanceIdentity identity = new InstanceIdentity("fake-self", livePid, liveStart);
        registry = new PeerRegistry(paths, identity);
    }

    private void putPeer(String id, long pid, long startedAt, String current) throws IOException {
        JsonObject peer = new JsonObject();
        peer.addProperty("id", id);
        peer.addProperty("pid", pid);
        peer.addProperty("startedAt", startedAt);
        if (current != null) {
            peer.addProperty("current", current);
        }
        Files.createDirectories(paths.peersDir());
        Files.writeString(paths.peerFile(id), peer.toString());
    }

    @Test
    void publish_writesOwnEntry() throws IOException {
        setUp();
        registry.publish();

        Path file = paths.peerFile("fake-self");
        assertThat(file).exists();
        JsonObject peer = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertThat(peer.get("id").getAsString()).isEqualTo("fake-self");
        assertThat(peer.get("pid").getAsLong()).isEqualTo(livePid);
    }

    @Test
    void setCurrent_recordsKeyAndPublishes() throws IOException {
        setUp();
        registry.setCurrent("offline:fake-key");

        assertThat(registry.currentKey()).isEqualTo("offline:fake-key");
        JsonObject peer = JsonParser.parseString(Files.readString(paths.peerFile("fake-self"))).getAsJsonObject();
        assertThat(peer.get("current").getAsString()).isEqualTo("offline:fake-key");
    }

    @Test
    void livePeers_excludesSelf() {
        setUp();
        registry.publish();

        assertThat(registry.livePeers()).isEmpty();
    }

    @Test
    void livePeers_includesLiveOther() throws IOException {
        setUp();
        putPeer("fake-other", livePid, liveStart, null);

        assertThat(registry.livePeers()).hasSize(1);
        assertThat(registry.livePeers().get(0).get("id").getAsString()).isEqualTo("fake-other");
    }

    @Test
    void livePeers_dropsAndDeletesDeadPeer() throws IOException {
        setUp();
        putPeer("fake-dead", 0L, 0L, null);

        assertThat(registry.livePeers()).isEmpty();
        assertThat(paths.peerFile("fake-dead")).doesNotExist();
    }

    @Test
    void livePeers_dropsPeerWhoseStartTimeDoesNotMatch() throws IOException {
        setUp();
        // pid 活着但启动时刻对不上：pid 被复用了，不是原来那个实例。
        putPeer("fake-reused", livePid, 1L, null);

        assertThat(registry.livePeers()).isEmpty();
        assertThat(paths.peerFile("fake-reused")).doesNotExist();
    }

    @Test
    void isUsedByOther_trueWhenLivePeerHoldsKey() throws IOException {
        setUp();
        putPeer("fake-other", livePid, liveStart, "offline:fake-key");

        assertThat(registry.isUsedByOther("offline:fake-key")).isTrue();
        assertThat(registry.isUsedByOther("offline:other")).isFalse();
    }

    @Test
    void isUsedByOther_falseForNull() {
        setUp();
        assertThat(registry.isUsedByOther(null)).isFalse();
    }

    @Test
    void removeSelf_deletesOwnEntry() {
        setUp();
        registry.publish();
        registry.removeSelf();

        assertThat(paths.peerFile("fake-self")).doesNotExist();
    }

    @Test
    void livePeers_ignoresCorruptFile() throws IOException {
        setUp();
        Files.createDirectories(paths.peersDir());
        Files.writeString(paths.peerFile("fake-corrupt"), "not json");

        assertThat(registry.livePeers()).isEmpty();
    }
}
