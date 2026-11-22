package top.syshub.accountsx.gametest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Reads the current screen across the two API generations covered by client
 * gametests: 26.x exposes {@code Gui.screen()}, 1.21.x only the
 * {@code Minecraft.screen} field. Everything else in the shared tests sticks
 * to the cross-version API intersection (REFACTORING/10-testing-strategy.md §5.3).
 */
public final class Screens {
    private Screens() {
    }

    public static Screen current(Minecraft client) {
        try {
            Object gui = Minecraft.class.getField("gui").get(client);
            return (Screen) gui.getClass().getMethod("screen").invoke(gui);
        } catch (ReflectiveOperationException noGuiScreen) {
            try {
                return (Screen) Minecraft.class.getField("screen").get(client);
            } catch (ReflectiveOperationException noField) {
                throw new AssertionError("Cannot read the current screen on this Minecraft version", noField);
            }
        }
    }
}
