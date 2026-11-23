package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.ServerScreen;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.manager.AccountManager;

import java.io.IOException;
import java.util.List;

/**
 * Acceptance point 3 (server move): adding two external servers and reordering them with
 * the sidebar up/down buttons must change the persisted server order, not just the account
 * list. Two fake servers with distinct display names make the filter buttons tellable apart.
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

            // Select B and move it above A.
            context.clickScreenButton("GtServerB");
            context.clickScreenButton("\u2191");
            assertOrder(b.base, a.base);

            // Move B back down to the bottom.
            context.clickScreenButton("\u2193");
            assertOrder(a.base, b.base);

            // Select A and move it down past B.
            context.clickScreenButton("GtServerA");
            context.clickScreenButton("\u2193");
            assertOrder(b.base, a.base);

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
}
