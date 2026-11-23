package top.syshub.accountsx.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.ExternalLoginScreen;
import top.syshub.accountsx.adapters.mc.ui.ServerScreen;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.manager.AccountManager;

import java.util.UUID;

/**
 * The external-server acceptance flow shared by the plain client gametest and the
 * authlib-injector compatibility run: add a Yggdrasil server through the real UI, log in
 * with password, verify the session switch writes name + UUID + token, delete the single
 * account (server-side invalidate, server entry survives), re-add it, then remove the
 * server and verify the cascading account cleanup. The caller owns the fake server and
 * must return to the title screen afterwards.
 */
final class ExternalAccountFlow {
    private ExternalAccountFlow() {
    }

    static void run(ClientGameTestContext context, FakeYggdrasilServer fake) {
        context.waitForScreen(TitleScreen.class);
        context.setScreen(() -> new AccountScreen(new TitleScreen()));

        addServer(context, fake);
        loginExternal(context, fake);

        // Delete the SINGLE account: the token must be invalidated server-side, the account
        // must go, but the server entry and its filter must survive (only removing the server
        // cascades). signOut runs on the worker, so wait for the invalidate hit.
        Screens.deleteRow(context, 0);
        context.waitFor(client -> AccountManager.getAccountsView().stream()
            .noneMatch(account -> account.getAccountType() == AccountType.AUTHLIB_INJECTOR));
        context.waitFor(client -> fake.hits("POST /authserver/invalidate") >= 1);
        if (AccountManager.authServers().byApiRoot(fake.base) == null) {
            throw new AssertionError("Deleting one account must not remove its server");
        }
        if (!context.tryClickScreenButton("GtYggdrasil")) {
            throw new AssertionError("Server filter must survive deleting its only account");
        }

        // Re-add the account so server removal still exercises the cascading cleanup.
        loginExternal(context, fake);
        context.clickScreenButton("accountsx.account.general.remove_auth_server");
        context.waitFor(client -> AccountManager.getAccountsView().stream()
            .noneMatch(account -> account.getAccountType() == AccountType.AUTHLIB_INJECTOR));
        if (AccountManager.authServers().byApiRoot(fake.base) != null) {
            throw new AssertionError("Server entry survived removal");
        }
        if (context.tryClickScreenButton("GtYggdrasil")) {
            throw new AssertionError("Server filter button survived removal");
        }
        context.waitFor(client -> fake.hits("POST /authserver/invalidate") >= 2);
    }

    private static void addServer(ClientGameTestContext context, FakeYggdrasilServer fake) {
        context.clickScreenButton("accountsx.account.general.add_auth_server");
        context.waitForScreen(ServerScreen.class);
        Screens.focusEditBox(context, 0);
        context.getInput().typeChars(fake.base);
        context.clickScreenButton("accountsx.account.general.add_server");
        context.waitForScreen(AccountScreen.class);
        if (AccountManager.authServers().byApiRoot(fake.base) == null) {
            throw new AssertionError("Probed server was not registered: " + fake.base);
        }
    }

    private static void loginExternal(ClientGameTestContext context, FakeYggdrasilServer fake) {
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

        // Acceptance 5: the switch must land in Minecraft's session variables — name AND
        // profile UUID — and the account must hold the token the fake server issued.
        context.waitFor(client -> client.getUser().getName().equals(FakeYggdrasilServer.PLAYER));
        UUID expected = UUID.fromString(FakeYggdrasilServer.UUID32.replaceFirst(
            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
        UUID sessionUuid = context.computeOnClient(client -> client.getUser().getProfileId());
        if (!expected.equals(sessionUuid)) {
            throw new AssertionError("External switch wrote the wrong profile UUID: " + sessionUuid);
        }
        BaseAccount current = AccountManager.getCurrentAccount();
        if (current == null || current.getAccountType() != AccountType.AUTHLIB_INJECTOR) {
            throw new AssertionError("Current account is not the external account: " + current);
        }
        if (!"fake-access".equals(current.getAccountStorage().getAccessToken())) {
            throw new AssertionError("External account did not persist the issued access token");
        }
        if (Screens.listSize(context) != 1) {
            throw new AssertionError("Server view should show exactly one account after login");
        }
        if (fake.hits("POST /authserver/authenticate") < 1) {
            throw new AssertionError("Login never reached the fake authserver");
        }
    }
}
