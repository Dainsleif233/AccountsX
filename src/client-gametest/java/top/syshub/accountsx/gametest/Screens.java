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

    public static net.minecraft.client.gui.components.ObjectSelectionList<?> accountList(Minecraft client) {
        for (var child : current(client).children()) {
            if (child instanceof net.minecraft.client.gui.components.ObjectSelectionList<?> list) {
                return list;
            }
        }
        throw new AssertionError("Account screen has no account list widget");
    }

    public static int listSize(ClientGameTestContext context) {
        return context.computeOnClient(client -> accountList(client).children().size());
    }

    /**
     * Non-empty button labels of the current screen, top to bottom. The account-list rows live
     * inside one ObjectSelectionList child, so this reads the sidebar filter buttons (and the
     * add/remove/reorder buttons) — enough to assert the sidebar reflects the server order.
     */
    public static java.util.List<String> buttonLabelsByY(Minecraft client) {
        return current(client).children().stream()
                .filter(w -> w instanceof net.minecraft.client.gui.components.AbstractWidget)
                .map(w -> (net.minecraft.client.gui.components.AbstractWidget) w)
                .sorted(java.util.Comparator.comparingInt(net.minecraft.client.gui.components.AbstractWidget::getY))
                .map(w -> w.getMessage().getString())
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Click the body of row {@code index} (left of the action buttons) to select/switch that account. */
    public static void clickRow(ClientGameTestContext context, int index) {
        clickAt(context, client -> {
            var list = accountList(client);
            return new double[] { list.getRowLeft() + 40, list.getRowTop(index) + 15 };
        });
    }

    /** Click the 'x' delete action of row {@code index}. */
    public static void deleteRow(ClientGameTestContext context, int index) {
        clickAt(context, client -> {
            var list = accountList(client);
            return new double[] { list.getRowRight() - client.font.width("x"), list.getRowTop(index) + 1 + 15 };
        });
    }

    private static void clickAt(ClientGameTestContext context, java.util.function.Function<Minecraft, double[]> guiPoint) {
        double[] point = context.computeOnClient(guiPoint::apply);
        double scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        // setCursorPos takes window pixels; GUI coordinates must be scaled. MOUSE_BUTTON_LEFT
        // differs between GLFW (0) and SDL (1) generations, so never hardcode the button number.
        context.getInput().setCursorPos(point[0] * scale, point[1] * scale);
        context.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().releaseMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
    }
}
