package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.TitleScreen;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.ExternalLoginScreen;
import top.syshub.accountsx.adapters.mc.ui.ServerScreen;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.manager.AccountManager;

/**
 * The external-server acceptance flow shared by the plain client gametest and the
 * authlib-injector compatibility run: add a Yggdrasil server through the real UI,
 * log in with password, verify the session switch, then remove the server and verify
 * the cascading account cleanup and the server-side token invalidation request.
 * The caller owns the fake server (ephemeral port, or the fixed port the injector
 * agent is pointed at) and must return to the title screen afterwards.
 */
final class ExternalAccountFlow {
    private ExternalAccountFlow() {
    }

    static void run(ClientGameTestContext context, FakeYggdrasilServer fake) {
        context.waitForScreen(TitleScreen.class);
        context.setScreen(() -> new AccountScreen(new TitleScreen()));

        // Add the server: probe must succeed against the fake root metadata.
        context.clickScreenButton("accountsx.account.general.add_auth_server");
        context.waitForScreen(ServerScreen.class);
        Screens.focusEditBox(context, 0);
        context.getInput().typeChars(fake.base);
        context.clickScreenButton("accountsx.account.general.add_server");
        context.waitForScreen(AccountScreen.class);
        if (AccountManager.authServers().byApiRoot(fake.base) == null) {
            throw new AssertionError("Probed server was not registered: " + fake.base);
        }

        // Log in with password on the new server's filter view.
        context.clickScreenButton("GtYggdrasil");
        context.clickScreenButton("accountsx.account.general.add_account");
        context.waitForScreen(ExternalLoginScreen.class);
        Screens.focusEditBox(context, 0);
        context.getInput().typeChars("fake-user");
        Screens.focusEditBox(context, 1);
        context.getInput().typeChars("fake-pass");
        if (!"fake-user".equals(Screens.editBoxValue(context, 0))
                || !"fake-pass".equals(Screens.editBoxValue(context, 1))) {
            throw new AssertionError("Login inputs did not take the typed text");
        }
        context.clickScreenButton("accountsx.account.general.login");
        context.waitForScreen(AccountScreen.class);

        // Acceptance 5: the switch must land in Minecraft's own session variables.
        context.waitFor(client -> client.getUser().getName().equals(FakeYggdrasilServer.PLAYER));
        BaseAccount current = AccountManager.getCurrentAccount();
        if (current == null || current.getAccountType() != AccountType.AUTHLIB_INJECTOR) {
            throw new AssertionError("Current account is not the external account: " + current);
        }
        if (listEntries(context) != 1) {
            throw new AssertionError("Server view should show exactly one account after login");
        }
        if (fake.hits("POST /authserver/authenticate") < 1) {
            throw new AssertionError("Login never reached the fake authserver");
        }

        // Remove the server: its accounts must go with it, and the token must be
        // invalidated server-side (signOut runs on the worker, so wait for the hit).
        context.clickScreenButton("accountsx.account.general.remove_auth_server");
        context.waitFor(client -> AccountManager.getAccountsView().stream()
            .noneMatch(account -> account.getAccountType() == AccountType.AUTHLIB_INJECTOR));
        if (AccountManager.authServers().byApiRoot(fake.base) != null) {
            throw new AssertionError("Server entry survived removal");
        }
        if (context.tryClickScreenButton("GtYggdrasil")) {
            throw new AssertionError("Server filter button survived removal");
        }
        context.waitFor(client -> fake.hits("POST /authserver/invalidate") >= 1);
    }

    private static int listEntries(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            for (var child : Screens.current(client).children()) {
                if (child instanceof ObjectSelectionList<?> list) {
                    return list.children().size();
                }
            }
            throw new AssertionError("Account screen has no account list widget");
        });
    }
}
