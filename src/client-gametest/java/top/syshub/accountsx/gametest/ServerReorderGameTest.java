package top.syshub.accountsx.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.ServerScreen;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.manager.AccountManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceptance point 3 (server move): adding two external servers and reordering them with the
 * sidebar up/down buttons must change the persisted server order, not just the account list.
 * Verifies three layers so a skipped save or an un-rebuilt sidebar cannot pass: the registry
 * order, the sidebar filter buttons actually shown, and the order written to the config file.
 */
public class ServerReorderGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitForScreen(TitleScreen.class);
        context.setScreen(() -> new AccountScreen(new TitleScreen()));

        try (FakeYggdrasilServer a = new FakeYggdrasilServer(0, "GtServerA");
             FakeYggdrasilServer b = new FakeYggdrasilServer(0, "GtServerB")) {
            addServer(context, a);
            addServer(context, b);
            assertOrder(a.base, b.base);
            assertSidebarOrder(context, "GtServerA", "GtServerB");

            // Select B and move it above A.
            context.clickScreenButton("GtServerB");
            context.clickScreenButton("\u2191");
            assertOrder(b.base, a.base);
            assertSidebarOrder(context, "GtServerB", "GtServerA");
            // The move must reach the disk, not just memory: a skipped save would leave [A, B].
            waitForDiskOrder(context, b.base, a.base);

            // Move B back down to the bottom.
            context.clickScreenButton("\u2193");
            assertOrder(a.base, b.base);
            assertSidebarOrder(context, "GtServerA", "GtServerB");

            // Select A and move it down past B.
            context.clickScreenButton("GtServerA");
            context.clickScreenButton("\u2193");
            assertOrder(b.base, a.base);
            assertSidebarOrder(context, "GtServerB", "GtServerA");
            waitForDiskOrder(context, b.base, a.base);

            context.takeScreenshot("server-reorder");

            // Clean up both servers: tests share one config file, and leftover servers push
            // later tests' filter buttons below the sidebar's visible scroll window.
            context.clickScreenButton("GtServerA");
            context.clickScreenButton("accountsx.account.general.remove_auth_server");
            context.waitFor(client -> AccountManager.authServers().byApiRoot(a.base) == null);
            context.clickScreenButton("GtServerB");
            context.clickScreenButton("accountsx.account.general.remove_auth_server");
            context.waitFor(client -> AccountManager.authServers().byApiRoot(b.base) == null);
        } catch (IOException e) {
            throw new AssertionError("Fake Yggdrasil servers failed", e);
        }

        context.setScreen(TitleScreen::new);
    }

    private static void addServer(ClientGameTestContext context, FakeYggdrasilServer fake) {
        context.clickScreenButton("accountsx.account.general.add_auth_server");
        context.waitForScreen(ServerScreen.class);
        Screens.focusEditBox(context, 0);
        context.getInput().typeChars(fake.base);
        context.clickScreenButton("accountsx.account.general.add_server");
        context.waitForScreen(AccountScreen.class);
        if (AccountManager.authServers().byApiRoot(fake.base) == null) {
            throw new AssertionError("Probed server was not registered: " + fake.base);
        }
    }

    private static void assertOrder(String... expected) {
        List<String> actual = AccountManager.authServers().servers().stream()
                .map(AuthServer::apiRoot)
                .toList();
        List<String> want = List.of(expected);
        if (!actual.equals(want)) {
            throw new AssertionError("Server order " + actual + " does not match " + want);
        }
    }

    /** The sidebar filter buttons must show the servers in the given relative order. */
    private static void assertSidebarOrder(ClientGameTestContext context, String... names) {
        List<String> labels = context.computeOnClient(Screens::buttonLabelsByY);
        int previous = -1;
        for (String name : names) {
            int at = labels.indexOf(name);
            if (at < 0) {
                throw new AssertionError("Sidebar does not show server button " + name + "; labels=" + labels);
            }
            if (at <= previous) {
                throw new AssertionError("Sidebar order wrong for " + name + "; labels=" + labels);
            }
            previous = at;
        }
    }

    /** Wait until the config file's servers array reflects the order (the save is async). */
    private static void waitForDiskOrder(ClientGameTestContext context, String... expected) {
        String configPath = System.getenv("ACCOUNTSX_CONFIG");
        if (configPath == null || configPath.isEmpty()) {
            throw new AssertionError("ACCOUNTSX_CONFIG is not set; cannot verify persisted order");
        }
        Path file = Path.of(configPath);
        List<String> want = List.of(expected);
        context.waitFor(client -> diskServerOrder(file).equals(want));
        if (!diskServerOrder(file).equals(want)) {
            throw new AssertionError("Persisted server order " + diskServerOrder(file) + " does not match " + want);
        }
    }

    private static List<String> diskServerOrder(Path file) {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonArray servers = root.has("servers") ? root.getAsJsonArray("servers") : new JsonArray();
            List<String> order = new ArrayList<>();
            servers.forEach(element -> {
                JsonObject server = element.getAsJsonObject();
                if (server.has("apiRoot")) {
                    order.add(server.get("apiRoot").getAsString());
                }
            });
            return order;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
