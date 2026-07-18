package net.burningtnt.accountsx.adapters.mc.ui;

import net.burningtnt.accountsx.core.accounts.impl.injector.AbstractInjectorAccount;
import net.burningtnt.accountsx.core.accounts.model.AccountType;
import net.burningtnt.accountsx.core.accounts.BaseAccount;
import net.burningtnt.accountsx.core.adapters.api.AccountSession;
import net.burningtnt.accountsx.core.manager.AccountManager;
import net.burningtnt.accountsx.core.manager.AccountWorker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static net.burningtnt.accountsx.core.AccountsX.LOGGER;

public class AccountListWidget extends AlwaysSelectedEntryListWidget<AccountListWidget.AccountEntry> {
    public AccountListWidget(MinecraftClient client, int left, int right, int top, int bottom, int entryHeight) {
        super(client, right - left, bottom - top, top, entryHeight);
        this.updateSize(left, right, top, bottom);

        syncAccounts();
    }

    public void updateSize(int left, int right, int top, int bottom) {
        this.setX(left);
        this.setY(top);
        this.setWidth(right - left);
        this.setHeight(bottom - top);
    }

    public void syncAccounts() {
        this.clearEntries();
        for (BaseAccount account : AccountManager.getAccountsView()) {
            AccountEntry entry = new AccountEntry(account);
            this.addEntry(entry);

            if (account == AccountManager.getCurrentAccount()) {
                super.setSelected(entry);
            }
        }
    }

    @Override
    public void setSelected(@Nullable AccountListWidget.AccountEntry entry) {
        if (entry != null) {
            BaseAccount account = entry.account;
            if (AccountManager.getCurrentAccount() != account) {
                AccountWorker.submit(() -> {
                    if (AccountManager.getCurrentAccount() == account) {
                        return;
                    }

                    AccountSession session = AccountManager.loginAccount(account);

                    client.send(() -> {
                        AccountManager.switchAccount(account, session);

                        super.setSelected(entry);
                        client.getNarratorManager().narrate((Text.translatable("narrator.select", entry.account.getAccountStorage().getPlayerName())).getString());
                    });
                });
            }
        } else {
            super.setSelected(null);
        }
    }

    @Override
    protected int getScrollbarX() {
        return this.getRight();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        AccountEntry entry = this.getSelectedOrNull();
        return entry != null && entry.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    public class AccountEntry extends Entry<AccountEntry> {
        private static final String ACTION_UP = "↑";

        private static final String ACTION_DELETE = "x";

        private static final String ACTION_DOWN = "↓";

        private final BaseAccount account;

        private Identifier avatarTextureId;

        private static final String ALEX_AVATAR = "iVBORw0KGgoAAAANSUhEUgAAALQAAAC0CAYAAAA9zQYyAAAFHklEQVR4Ae3AA6AkWZbG8f937o3IzKdyS2Oubdu2bdu2bdu2bWmMnpZKr54yMyLu+Xa3anqmhztr1a/e+RWvZf4T7Nx4Pf9ZVrt3859hfvx6/rPs3Xk3/xn6Tf7TzI9fz3+GvTvv5j8Jlauu+r+DylVX/d9B5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVq676v4PKVVf930Hlqqv+76By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fQeWqq/7voHLVVf93ULnqqv87qP0m/+sMh/wnuZv/bYZD/hPdzX+GnRuv5z8Jlauu+r+DylVX/d9B5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVq676v4PKVVf930Hlqqv+76By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fQeWqq/7voHLVVf93ULnqqv87qFx11f8dVP6T7N15N/9Z+k3+UwyH/KcZXun9+F/nT76D/xx385+EylVX/d9B5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVq676v4PKVVf930Hlqqv+76By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fQeWqq/7voHLVVf93ULnqqv87qFx11f8d6Ow3v5b5X2Y45D9F/3ofw3+WunGM/wzT0SX+txl+46v4T0Llqqv+76By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fQeWqq/7voHLVVf93ULnqqv87qFx11f8dVK666v8OKldd9X8Hlauu+r+DylVX/d9B5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVV/4Y/jOcfqm34j/Lw9/vEfxn+PO3OMb/Nq/8tR/Pf5anfMeT+c9wjv80VK666v8OKldd9X8Hlauu+r+DylVX/d9B5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVq676v4PKVVf930Hlqqv+76By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fQeWqq/7vQBef9Fvmqqv+b6By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fQeWqq/7voHLVVf93ULnqqv87qFx11f8dVK666v8OKldd9X8Hlauu+r+DylVX/d9B5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVq676v4PKVc/2jD3+13nQDv/bTEeX+E9C5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVq676v4PKVVf930Hlqqv+76By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fQeWqq/7voHLVVf93ULnqqv87qFx11f8dVK666v8O6nR0if8MdeMY/+s8aIernm06usR/hoODPf6TULnqqv87qFx11f8dVK666v8OKldd9X8Hlauu+r+DylVX/d9B5aqr/u+gctVV/3dQueqq/zuoXHXV/x1Urrrq/w4qV131fweVq676v4PKVVf930Hlqqv+76By1VX/d1C56qr/O6hcddX/HVSuuur/DipXXfV/B5Wrrvq/g8pVV/3fwT8C13lAqg0KKHQAAAAASUVORK5CYII=";

        public AccountEntry(BaseAccount account) {
            this.account = account;
            this.avatarTextureId = loadAvatar(account.getAvatar());
            if (this.avatarTextureId == null) this.avatarTextureId = loadAvatar(ALEX_AVATAR);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            context.drawText(client.textRenderer, this.account.getAccountStorage().getPlayerName(), x + 32 + 3, y + 1, 0xFFFFFF, false);
            context.drawText(client.textRenderer, this.account.getAccountName() == null ? I18N.TRANSLATOR.translate(this.account.getAccountType()) : Text.of(this.account.getAccountName()), x + 32 + 3, y + 1 + 9, 0xFFFFFF, false);
            context.drawText(client.textRenderer, I18N.TRANSLATOR.translate(this.account.getAccountStorage().getState()), x + 32 + 3, y + 1 + 18, 0xFFFFFF, false);
            context.drawTexture(RenderLayer::getGuiTexturedOverlay, avatarTextureId, x, y, 1, 1, 30, 30, 32, 32);

            if (this.account.getAccountType() != AccountType.ENV_DEFAULT) {
                if (index > 1) {
                    context.drawText(client.textRenderer, ACTION_UP, (int) (x + entryWidth - 1.5 * client.textRenderer.getWidth(ACTION_UP)), y + 1 + 5 - client.textRenderer.fontHeight / 2, 0xFFFFFF, false);
                }

                context.drawText(client.textRenderer, ACTION_DELETE, (int) (x + entryWidth - 1.5 * client.textRenderer.getWidth(ACTION_DELETE)), y + 1 + 15 - client.textRenderer.fontHeight / 2, 0xFFFFFF, false);

                if (index < getEntryCount() - 1) {
                    context.drawText(client.textRenderer, ACTION_DOWN, (int) (x + entryWidth - 1.5 * client.textRenderer.getWidth(ACTION_DOWN)), y + 1 + 25 - client.textRenderer.fontHeight / 2, 0xFFFFFF, false);
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int right = getRowRight();
            int buttonW = client.textRenderer.getWidth("x");
            if (mouseX >= right - buttonW * 1.5 && mouseX <= right - buttonW * 0.5) {
                int index = children().indexOf(this);
                int top = getRowTop(index);

                if (this.account.getAccountType() != AccountType.ENV_DEFAULT) {
                    if (index > 1) {
                        int btnTop = top + 1 + 5 - client.textRenderer.fontHeight / 2;
                        if (mouseY >= btnTop && mouseY <= btnTop + client.textRenderer.fontHeight) {
                            AccountManager.moveAccount(this.account, index - 1);
                            AccountListWidget.this.syncAccounts();
                            return false;
                        }
                    }

                    int btnTop = top + 1 + 15 - client.textRenderer.fontHeight / 2;
                    if (mouseY >= btnTop && mouseY <= btnTop + client.textRenderer.fontHeight) {
                        AccountManager.dropAccount(this.account);
                        AccountListWidget.this.syncAccounts();
                        return false;
                    }

                    if (index < getEntryCount() - 1) {
                        int btnTop2 = top + 1 + 25 - client.textRenderer.fontHeight / 2;
                        if (mouseY >= btnTop2 && mouseY <= btnTop2 + client.textRenderer.fontHeight) {
                            AccountManager.moveAccount(this.account, index + 1);
                            AccountListWidget.this.syncAccounts();
                            return false;
                        }
                    }
                }
            }

            setSelected(this);
            return false;
        }

        @Override
        public Text getNarration() {
            return Text.of("");
        }

        private Identifier loadAvatar(String avatarBase64) {
            try {
                if (avatarBase64 == null || avatarBase64.isEmpty())
                    return null;

                byte[] imageBytes = Base64.getDecoder().decode(avatarBase64);
                NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(imageBytes));
                NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);

                AccountType type = account.getAccountType();
                UUID uuid = account.getAccountStorage().getPlayerUUID();
                String server = type == AccountType.AUTHLIB_INJECTOR || type == AccountType.UNITED_INJECTOR ?
                        ((AbstractInjectorAccount) account).getServer() : "";
                String textureKey = type.toString() + "_" + uuid.toString() + "_" + server;
                UUID textureUUID = UUID.nameUUIDFromBytes(textureKey.getBytes(StandardCharsets.UTF_8));

                Identifier identifier = Identifier.of("accountsx", "avatar_" + textureUUID);
                client.getTextureManager().registerTexture(identifier, texture);
                return identifier;
            } catch (IOException e) {
                LOGGER.error("Failed to load avatar for {}: {}", account.getAccountStorage().getPlayerName(), e.getMessage());
                return null;
            }
        }
    }
}
