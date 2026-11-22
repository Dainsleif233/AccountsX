package top.syshub.accountsx.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.TitleScreen;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.OfflineLoginScreen;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.manager.AccountManager;

/**
 * Acceptance points 4/5 (offline branch): add an offline account through the real
 * login UI, verify the switch writes the Minecraft session variables, then remove
 * it through the list's delete action. Microsoft login needs a human and stays manual.
 */
public class OfflineAccountLifecycleGameTest implements FabricClientGameTest {
    private static final String PLAYER = "GtOffline";

    @Override
    public void runTest(ClientGameTestContext context) {
        context.waitForScreen(TitleScreen.class);
        context.setScreen(() -> new AccountScreen(new TitleScreen()));

        context.clickScreenButton("accountsx.account.filter.offline");
        context.clickScreenButton("accountsx.account.general.add_account");
        context.waitForScreen(OfflineLoginScreen.class);

        // Focus the player-name box (first EditBox) and type through the real input path.
        Screens.focusEditBox(context, 0);
        context.getInput().typeChars(PLAYER);
        String typed = Screens.editBoxValue(context, 0);
        if (!PLAYER.equals(typed)) {
            throw new AssertionError("Name input did not take the typed text: '" + typed + "'");
        }

        context.clickScreenButton("accountsx.account.general.login");
        context.waitForScreen(AccountScreen.class);

        // Acceptance 5: the switch must land in Minecraft's own session variables.
        context.waitFor(client -> client.getUser().getName().equals(PLAYER));
        BaseAccount current = AccountManager.getCurrentAccount();
        if (current == null || current.getAccountType() != AccountType.OFFLINE
                || !PLAYER.equals(current.getAccountStorage().getPlayerName())) {
            throw new AssertionError("Current account is not the freshly added offline account: " + current);
        }
        if (listEntries(context) != 1) {
            throw new AssertionError("Account list should show exactly one entry after login");
        }

        // Delete through the list's 'x' action: right edge of row 0, middle text line.
        double[] deleteAt = context.computeOnClient(client -> {
            ObjectSelectionList<?> list = findList(client);
            int x = list.getRowRight() - client.font.width("x");
            int y = list.getRowTop(0) + 1 + 15;
            return new double[] { x, y };
        });
        double scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        context.getInput().setCursorPos(deleteAt[0] * scale, deleteAt[1] * scale);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);

        context.waitFor(client -> AccountManager.getAccountsView().stream()
            .noneMatch(account -> account.getAccountType() == AccountType.OFFLINE));
        if (listEntries(context) != 0) {
            throw new AssertionError("Account list should be empty after deleting the only account");
        }

        context.takeScreenshot("offline-account-lifecycle");
        context.setScreen(TitleScreen::new);
    }

    private static int listEntries(ClientGameTestContext context) {
        return context.computeOnClient(client -> findList(client).children().size());
    }

    private static ObjectSelectionList<?> findList(net.minecraft.client.Minecraft client) {
        for (var child : Screens.current(client).children()) {
            if (child instanceof ObjectSelectionList<?> list) {
                return list;
            }
        }
        throw new AssertionError("Account screen has no account list widget");
    }
}
