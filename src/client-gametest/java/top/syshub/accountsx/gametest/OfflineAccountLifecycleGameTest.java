package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.OfflineLoginScreen;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.manager.AccountManager;

import java.util.UUID;

/**
 * Acceptance points 4/5 (offline branch): add offline accounts through the real login
 * UI, switch between them by clicking the list (not just the login auto-switch), verify
 * the switch writes both the name and the UUID into Minecraft's session, then delete the
 * CURRENT account and verify the game falls back to the launcher session. Microsoft login
 * needs a human and stays manual.
 */
public class OfflineAccountLifecycleGameTest implements FabricClientGameTest {
    private static final String PLAYER_A = "GtOffline";
    private static final String PLAYER_B = "GtOfflineB";

    @Override
    public void runTest(ClientGameTestContext context) {
        // The injector run attaches authlib-injector pointed at a fixed port that only the
        // compatibility test serves. Running this test there would make the mod's authlib
        // fallback hit a dead port; it belongs to the plain run. Mirror of the compatibility
        // test skipping the plain run.
        if (Boolean.getBoolean("accountsx.gametest.injector")) {
            return;
        }
        context.waitForScreen(TitleScreen.class);
        // The launcher session we must fall back to after deleting the current account.
        String launcherName = context.computeOnClient(client -> client.getUser().getName());
        context.setScreen(() -> new AccountScreen(new TitleScreen()));

        UUID uuidA = addOffline(context, PLAYER_A);
        addOffline(context, PLAYER_B);
        // Offline filter view now lists [A, B]; B was auto-selected by its login.
        if (Screens.listSize(context) != 2) {
            throw new AssertionError("Offline view should list two accounts after adding both");
        }

        // Acceptance 5: switch back to A by CLICKING its row, and verify both session
        // variables (name AND profile UUID) — a broken list-switch or wrong credential
        // would leave the name or the UUID stale and must fail here.
        Screens.clickRow(context, 0);
        context.waitFor(client -> client.getUser().getName().equals(PLAYER_A));
        UUID switchedUuid = context.computeOnClient(client -> client.getUser().getProfileId());
        if (!uuidA.equals(switchedUuid)) {
            throw new AssertionError("Clicking row A wrote the wrong profile UUID: " + switchedUuid + " != " + uuidA);
        }
        BaseAccount current = AccountManager.getCurrentAccount();
        if (current == null || !PLAYER_A.equals(current.getAccountStorage().getPlayerName())) {
            throw new AssertionError("Current account is not A after clicking its row: " + current);
        }

        // Delete the CURRENT account (A): the pointer must fall back to the launcher
        // session and Minecraft's user must revert to the launcher name. B survives.
        Screens.deleteRow(context, 0);
        context.waitFor(client -> {
            BaseAccount c = AccountManager.getCurrentAccount();
            return c != null && c.getAccountType() == AccountType.ENV_DEFAULT;
        });
        context.waitFor(client -> client.getUser().getName().equals(launcherName));
        if (Screens.listSize(context) != 1) {
            throw new AssertionError("Offline view should still list B after deleting A");
        }

        context.takeScreenshot("offline-account-lifecycle");
        context.setScreen(TitleScreen::new);
    }

    /** Adds an offline account through the login UI and returns its profile UUID. */
    private static UUID addOffline(ClientGameTestContext context, String name) {
        context.clickScreenButton("accountsx.account.filter.offline");
        context.clickScreenButton("accountsx.account.general.add_account");
        context.waitForScreen(OfflineLoginScreen.class);
        Screens.focusEditBox(context, 0);
        context.getInput().typeChars(name);
        if (!name.equals(Screens.editBoxValue(context, 0))) {
            throw new AssertionError("Name input did not take the typed text for " + name);
        }
        context.clickScreenButton("accountsx.account.general.login");
        context.waitForScreen(AccountScreen.class);
        context.waitFor(client -> client.getUser().getName().equals(name));
        BaseAccount current = AccountManager.getCurrentAccount();
        if (current == null || current.getAccountType() != AccountType.OFFLINE
                || !name.equals(current.getAccountStorage().getPlayerName())) {
            throw new AssertionError("Current account is not the freshly added " + name + ": " + current);
        }
        UUID uuid = current.getAccountStorage().getPlayerUUID();
        UUID sessionUuid = context.computeOnClient(client -> client.getUser().getProfileId());
        if (!uuid.equals(sessionUuid)) {
            throw new AssertionError("Login auto-switch wrote the wrong UUID for " + name);
        }
        // 选中框必须跟着自动切换走：入库时列表还停在旧当前账号，切换后不通知就会留在旧框。
        if (Screens.selectedAccount(context) != current) {
            throw new AssertionError("Selection box did not follow the auto-switch to " + name);
        }
        return uuid;
    }
}
