package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Acceptance point 6: the mod must keep working when a launcher loads
 * authlib-injector as a -javaagent. This run configuration (see Loom.kt) attaches
 * the agent pointed at {@link #PORT} with prefetched root metadata, so the game
 * boots with authlib's URL constants already redirected. The external flow then
 * exercises the real compatibility surface: session construction and signOut go
 * through authlib classes the agent has transformed.
 *
 * <p>Only registered in the authlib-injector run configuration's test mod; the
 * plain clientGameTest run does not load the agent and must not run this class.
 */
public class AuthlibInjectorCompatibilityGameTest implements FabricClientGameTest {
    /** Must match the API root baked into the injector run configuration. */
    static final int PORT = 25599;

    @Override
    public void runTest(ClientGameTestContext context) {
        // This class lives in the shared test source set, so the plain clientGameTest
        // run also discovers it. The injector run configuration sets this marker; without
        // it the test is not applicable and skips. With it, the agent MUST be present —
        // a missing agent then fails loudly instead of silently skipping.
        if (!Boolean.getBoolean("accountsx.gametest.injector")) {
            return;
        }
        // -javaagent appends the agent jar to the SYSTEM classpath, but Fabric's Knot
        // classloader does not expose it to game classes, so Class.forName (caller = Knot)
        // cannot see it. Load through the system classloader, which is where the agent lives.
        try {
            ClassLoader.getSystemClassLoader().loadClass("moe.yushi.authlibinjector.AuthlibInjector");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("authlib-injector agent is not loaded despite the injector run marker", e);
        }

        try (FakeYggdrasilServer fake = new FakeYggdrasilServer(PORT)) {
            ExternalAccountFlow.run(context, fake);
            context.takeScreenshot("authlib-injector-compatibility");
        } catch (java.io.IOException e) {
            throw new AssertionError("Fake Yggdrasil server failed on port " + PORT, e);
        }
        context.setScreen(TitleScreen::new);
    }
}
