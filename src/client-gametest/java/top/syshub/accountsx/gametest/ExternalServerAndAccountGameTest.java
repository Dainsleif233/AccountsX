package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Acceptance points 3/4/5 (external branch) on a plain client: add a Yggdrasil
 * server through the real UI against a local fake server, log in with password,
 * verify the session switch, then remove the server and verify the cascading
 * account cleanup and the server-side token invalidation request.
 */
public class ExternalServerAndAccountGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        // Belongs to the plain run: the injector run only exercises the compatibility test,
        // which serves the fixed authlib-injector port itself. See AuthlibInjectorCompatibilityGameTest.
        if (Boolean.getBoolean("accountsx.gametest.injector")) {
            return;
        }
        try (FakeYggdrasilServer fake = new FakeYggdrasilServer()) {
            ExternalAccountFlow.run(context, fake);
            context.takeScreenshot("external-server-and-account");
        } catch (java.io.IOException e) {
            throw new AssertionError("Fake Yggdrasil server failed", e);
        }
        context.setScreen(TitleScreen::new);
    }
}
