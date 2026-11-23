package top.syshub.accountsx.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.manager.AccountManager;

import java.util.List;

/**
 * Acceptance point 2: the title-screen entry icon opens the account list with
 * the expected content. The entry button has an empty message (tooltip only),
 * so {@code clickScreenButton} cannot find it; locate it structurally and click
 * with real mouse input instead.
 */
public class TitleScreenEntryGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitForScreen(TitleScreen.class);

        // The only empty-message widget on the title screen is our entry icon:
        // vanilla language/accessibility/friends buttons all carry text.
        double[] center = context.computeOnClient(client -> {
            AbstractWidget entry = null;
            for (var child : Screens.current(client).children()) {
                if (child instanceof AbstractWidget widget && widget.getMessage().getString().isEmpty()) {
                    if (entry != null) {
                        throw new AssertionError("More than one empty-message widget on the title screen");
                    }
                    entry = widget;
                }
            }
            if (entry == null) {
                throw new AssertionError("AccountsX entry icon missing from the title screen");
            }
            // The icon must carry a sprite, not render as a blank button. SpriteIconButton
            // stores it in a 'sprite' field; assert it was assigned (a missing texture file
            // still shows in the captured screenshot, which is reviewed as an artifact).
            for (Class<?> c = entry.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field sprite = c.getDeclaredField("sprite");
                    sprite.setAccessible(true);
                    if (sprite.get(entry) == null) {
                        throw new AssertionError("Entry icon has no sprite assigned");
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                    // walk up to SpriteIconButton
                } catch (IllegalAccessException ex) {
                    throw new AssertionError("Cannot read entry icon sprite", ex);
                }
            }
            return new double[] {
                entry.getX() + entry.getWidth() / 2.0,
                entry.getY() + entry.getHeight() / 2.0
            };
        });

        // setCursorPos takes window pixels; GUI coordinates must be scaled.
        double scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        // MOUSE_BUTTON_LEFT is 0 on GLFW versions (<=1.21.11) but 1 on SDL versions (26.x);
        // isValidClickButton rejects the wrong numbering, so never hardcode 0.
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);

        context.waitForScreen(AccountScreen.class);
        String title = context.computeOnClient(client -> Screens.current(client).getTitle().getString());
        if (!title.equals(Component.translatable("accountsx.account.general.account_list").getString())) {
            throw new AssertionError("Account list title mismatch: " + title);
        }

        boolean hasAllFilter = context.computeOnClient(client -> Screens.current(client).children().stream()
            .anyMatch(child -> child instanceof Button button
                && button.getMessage().getString()
                    .equals(Component.translatable("accountsx.account.filter.all").getString())));
        if (!hasAllFilter) {
            throw new AssertionError("Account list is missing the 'all accounts' filter button");
        }

        // Opening the screen is not enough: the list must render real rows, not open blank.
        // The launcher (environment) account is always in the all-accounts view, so the widget
        // must have built one entry per account and every row must carry a non-empty name.
        int renderedRows = context.computeOnClient(client -> Screens.accountList(client).children().size());
        List<BaseAccount> view = AccountManager.getAccountsView();
        if (renderedRows < 1 || renderedRows != view.size()) {
            throw new AssertionError("Account list rendered " + renderedRows + " rows for " + view.size() + " accounts");
        }
        for (BaseAccount account : view) {
            String name = account.getAccountStorage().getPlayerName();
            if (name == null || name.isEmpty()) {
                throw new AssertionError("Account list row has an empty player name: " + account);
            }
        }

        context.takeScreenshot("title-screen-entry-opens-account-list");
        // The framework asserts every test finishes back on the title screen.
        context.setScreen(TitleScreen::new);
    }
}
