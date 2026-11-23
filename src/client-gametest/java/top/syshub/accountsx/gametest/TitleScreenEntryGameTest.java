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

import java.lang.reflect.Field;
import java.util.List;

/**
 * Acceptance point 2: the title-screen entry icon renders and opens the account list with
 * real content. The entry button has an empty message (tooltip only), so
 * {@code clickScreenButton} cannot find it; locate it structurally and click with real
 * mouse input. The title screen is captured BEFORE the click so the icon itself is reviewed.
 */
public class TitleScreenEntryGameTest implements FabricClientGameTest {
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
        // Capture the title screen first: a screenshot taken after opening the account list
        // would not show whether the entry icon actually rendered.
        context.takeScreenshot("title-screen-entry-icon");

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
            assertSpritePresent(client, entry);
            return new double[] {
                entry.getX() + entry.getWidth() / 2.0,
                entry.getY() + entry.getHeight() / 2.0
            };
        });

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

        // The list must render real rows, not open blank. Read the account each rendered row
        // actually holds (the widget's own row data, not the global model) and require a name.
        int renderedRows = context.computeOnClient(client -> {
            var rows = Screens.accountList(client).children();
            for (var row : rows) {
                BaseAccount account = rowAccount(row);
                String name = account.getAccountStorage().getPlayerName();
                if (name == null || name.isEmpty()) {
                    throw new AssertionError("Rendered account row has an empty player name: " + row);
                }
            }
            return rows.size();
        });
        List<BaseAccount> view = AccountManager.getAccountsView();
        if (renderedRows < 1 || renderedRows != view.size()) {
            throw new AssertionError("Account list rendered " + renderedRows + " rows for " + view.size() + " accounts");
        }

        context.takeScreenshot("title-screen-entry-opens-account-list");
        context.setScreen(TitleScreen::new);
    }

    /** The icon must carry a sprite AND its texture resource must exist; a blank button fails. */
    private static void assertSpritePresent(net.minecraft.client.Minecraft client, AbstractWidget entry) {
        Object sprite = null;
        boolean found = false;
        for (Class<?> c = entry.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("sprite");
                field.setAccessible(true);
                sprite = field.get(entry);
                found = true;
                break;
            } catch (NoSuchFieldException ignored) {
                // walk up to SpriteIconButton
            } catch (IllegalAccessException ex) {
                throw new AssertionError("Cannot read entry icon sprite", ex);
            }
        }
        if (!found) {
            throw new AssertionError("Entry icon class hierarchy has no sprite field: " + entry.getClass());
        }
        if (sprite == null) {
            throw new AssertionError("Entry icon has no sprite assigned");
        }
        // 26.x stores a WidgetSprites (get(false,false) -> enabled Identifier); 1.21.x stores the
        // ResourceLocation directly. Either way, resolve the texture and require the resource exists.
        Object identifier;
        try {
            identifier = sprite.getClass().getMethod("get", boolean.class, boolean.class).invoke(sprite, false, false);
        } catch (NoSuchMethodException notWidgetSprites) {
            identifier = sprite;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot resolve entry icon sprite identifier", ex);
        }
        if (!resourceExists(client, identifier)) {
            throw new AssertionError("Entry icon sprite texture is missing: " + identifier);
        }
    }

    /** Resolve a sprite identifier to its textures/gui/sprites PNG and require the resource stack is non-empty. */
    private static boolean resourceExists(net.minecraft.client.Minecraft client, Object identifier) {
        try {
            String namespace = (String) identifier.getClass().getMethod("getNamespace").invoke(identifier);
            String path = (String) identifier.getClass().getMethod("getPath").invoke(identifier);
            String texturePath = "textures/gui/sprites/" + path + ".png";
            Object location = newResourceLocation(client, namespace, texturePath);
            Object manager = client.getResourceManager();
            for (var method : manager.getClass().getMethods()) {
                if (method.getName().equals("getResourceStack") && method.getParameterCount() == 1) {
                    Object stack = method.invoke(manager, location);
                    return stack instanceof List<?> list && !list.isEmpty();
                }
            }
            throw new AssertionError("ResourceManager has no getResourceStack method");
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Cannot check entry icon texture resource", ex);
        }
    }

    /** Build a ResourceLocation (1.21.x) or Identifier (26.x) via the version's fromNamespaceAndPath factory. */
    private static Object newResourceLocation(net.minecraft.client.Minecraft client, String namespace, String path)
            throws ReflectiveOperationException {
        Object manager = client.getResourceManager();
        Class<?> type = null;
        for (var method : manager.getClass().getMethods()) {
            if (method.getName().equals("getResourceStack") && method.getParameterCount() == 1) {
                type = method.getParameterTypes()[0];
                break;
            }
        }
        if (type == null) {
            throw new AssertionError("Cannot determine the resource location type");
        }
        return type.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
    }

    /** Read the BaseAccount a rendered list row holds (AccountEntry.account), proving rows carry real data. */
    private static BaseAccount rowAccount(Object row) {
        for (Class<?> c = row.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("account");
                field.setAccessible(true);
                Object value = field.get(row);
                if (value instanceof BaseAccount account) {
                    return account;
                }
            } catch (NoSuchFieldException ignored) {
                // walk up
            } catch (IllegalAccessException ex) {
                throw new AssertionError("Cannot read account row data", ex);
            }
        }
        throw new AssertionError("Account list row has no account field: " + row.getClass());
    }
}
