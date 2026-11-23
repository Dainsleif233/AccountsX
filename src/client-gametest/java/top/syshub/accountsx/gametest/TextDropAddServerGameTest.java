package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import top.syshub.accountsx.common.auth.server.TextDropHandler;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.manager.AccountManager;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Drag-to-add (26.3+ SDL text drop): feeding the authlib-injector launcher-spec URI to the
 * drop handler must probe and register the server on the background worker, without any UI
 * navigation. The SDL event routing itself (DROP_TEXT -> handler) is a mixin verified by the
 * game booting; this exercises the handler -> parse -> background add -> registry wiring.
 */
public class TextDropAddServerGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("accountsx.gametest.injector")) {
            return;
        }
        context.waitForScreen(TitleScreen.class);
        context.setScreen(() -> new AccountScreen(new TitleScreen()));

        try (FakeYggdrasilServer fake = new FakeYggdrasilServer()) {
            String uri = "authlib-injector:yggdrasil-server:"
                    + URLEncoder.encode(fake.base, StandardCharsets.UTF_8);
            TextDropHandler.handle(uri);

            context.waitFor(client -> AccountManager.authServers().byApiRoot(fake.base) != null);
            AuthServer added = AccountManager.authServers().byApiRoot(fake.base);
            if (!"GtYggdrasil".equals(added.displayName())) {
                throw new AssertionError("Dropped server probed wrong displayName: " + added.displayName());
            }

            // 开着的账户界面必须立刻重建左栏显示新服务器。bug 是后台加服从不通知界面，
            // 筛选按钮要重开界面才出现；没有通知修复时这条 waitFor 会超时。
            context.waitFor(client -> Screens.buttonLabelsByY(client).contains("GtYggdrasil"));

            // Unrelated text must be ignored, not turned into a server or crash the worker.
            TextDropHandler.handle("just some random text");
            context.waitTicks(10);

            AccountManager.authServers().remove(added.kind(), added.apiRoot());
            context.waitFor(client -> AccountManager.authServers().byApiRoot(fake.base) == null);
        } catch (IOException e) {
            throw new AssertionError("Fake Yggdrasil server failed", e);
        }

        context.takeScreenshot("text-drop-add-server");
        context.setScreen(TitleScreen::new);
    }
}
