package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.UUID;

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
        Class<?> agent;
        try {
            agent = ClassLoader.getSystemClassLoader().loadClass("moe.yushi.authlibinjector.AuthlibInjector");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("authlib-injector agent is not loaded despite the injector run marker", e);
        }
        // Loading the class is not proof the agent ran: -javaagent triggers premain -> bootstrap,
        // which installs the class transformer that redirects Mojang URLs. If the JVM lost the
        // -javaagent argument (jar merely on the classpath, or args dropped), the transformer is
        // never installed and getClassTransformer() stays null — fail closed instead of green.
        try {
            Object transformer = agent.getMethod("getClassTransformer").invoke(null);
            if (transformer == null) {
                throw new AssertionError("authlib-injector never bootstrapped: no class transformer installed");
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot verify authlib-injector bootstrap state", e);
        }
        // The agent must be pointed at the fixed fake-server port this test binds; a run config
        // pointed elsewhere would not exercise the intended compatibility surface.
        String prefetched = System.getProperty("authlibinjector.yggdrasil.prefetched", "");
        if (prefetched.isEmpty()) {
            throw new AssertionError("Injector run is missing its prefetched-metadata JVM argument");
        }

        try (FakeYggdrasilServer fake = new FakeYggdrasilServer(PORT)) {
            // The external flow talks to the fake server through the mod's own HTTP gateway,
            // and signOut uses that gateway too — neither proves the agent redirected authlib.
            // Drive a profile fetch through Minecraft's INSTALLED session service (an authlib
            // object the agent transformed): if redirection took effect it lands on the fake
            // server's sessionserver endpoint, otherwise it goes to Mojang and never hits here.
            assertAuthlibRedirected(context, fake);
            ExternalAccountFlow.run(context, fake);
            context.takeScreenshot("authlib-injector-compatibility");
        } catch (java.io.IOException e) {
            throw new AssertionError("Fake Yggdrasil server failed on port " + PORT, e);
        }
        context.setScreen(TitleScreen::new);
    }

    private static void assertAuthlibRedirected(ClientGameTestContext context, FakeYggdrasilServer fake) {
        String profilePath = "GET /sessionserver/session/minecraft/profile/" + FakeYggdrasilServer.UUID32;
        int before = fake.hits(profilePath);
        UUID uuid = UUID.fromString(FakeYggdrasilServer.UUID32.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        context.computeOnClient(client -> {
            Object service = sessionService(client);
            try {
                service.getClass().getMethod("fetchProfile", UUID.class, boolean.class).invoke(service, uuid, false);
            } catch (NoSuchMethodException e) {
                throw new AssertionError("Installed session service has no fetchProfile(UUID, boolean)", e);
            } catch (ReflectiveOperationException e) {
                // A parse/auth failure AFTER the request reached the fake server still proves the
                // redirect; only a missing method (above) breaks the assumption.
            }
            return null;
        });
        context.waitFor(client -> fake.hits(profilePath) > before);
    }

    /** 1.21.x exposes the service directly; 26.x moved it into Minecraft.services().sessionService(). */
    private static Object sessionService(Minecraft client) {
        try {
            return Minecraft.class.getMethod("getMinecraftSessionService").invoke(client);
        } catch (ReflectiveOperationException noDirectGetter) {
            try {
                Object services = Minecraft.class.getMethod("services").invoke(client);
                return services.getClass().getMethod("sessionService").invoke(services);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot obtain the installed Minecraft session service", e);
            }
        }
    }
}
