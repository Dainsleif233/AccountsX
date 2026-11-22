package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
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

    /**
     * Focuses the index-th EditBox of the current screen. charTyped routes through
     * screen.getFocused(), so the screen-level focus must be set too —
     * box.setFocused(true) alone leaves the keystrokes unrouted.
     */
    public static void focusEditBox(ClientGameTestContext context, int index) {
        context.runOnClient(client -> {
            Screen screen = current(client);
            int i = 0;
            for (var child : screen.children()) {
                if (child instanceof EditBox box && i++ == index) {
                    screen.setFocused(box);
                    box.setFocused(true);
                    return;
                }
            }
            throw new AssertionError("No EditBox at index " + index + " on " + screen.getClass().getSimpleName());
        });
    }

    public static String editBoxValue(ClientGameTestContext context, int index) {
        return context.computeOnClient(client -> {
            int i = 0;
            for (var child : current(client).children()) {
                if (child instanceof EditBox box && i++ == index) {
                    return box.getValue();
                }
            }
            throw new AssertionError("No EditBox at index " + index);
        });
    }
}
