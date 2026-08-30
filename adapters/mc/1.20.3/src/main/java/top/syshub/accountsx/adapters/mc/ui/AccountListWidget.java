package top.syshub.accountsx.adapters.mc.ui;

import org.jetbrains.annotations.NotNull;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.core.accounts.model.AccountType;
import top.syshub.accountsx.core.adapters.api.AccountSession;
import top.syshub.accountsx.core.manager.AccountManager;
import top.syshub.accountsx.core.task.TaskScheduler;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import static top.syshub.accountsx.core.AccountsX.LOGGER;

import com.mojang.blaze3d.platform.NativeImage;
import top.syshub.accountsx.image.AvatarCache;

public class AccountListWidget extends ObjectSelectionList<AccountListWidget.AccountEntry> {
    public AccountListWidget(Minecraft client, int left, int right, int top, int bottom, int entryHeight) {
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
                TaskScheduler.submit(() -> {
                    if (AccountManager.getCurrentAccount() == account) {
                        return;
                    }

                    AccountSession session = AccountManager.loginAccount(account);

                    minecraft.tell(() -> {
                        AccountManager.switchAccount(account, session);

                        super.setSelected(entry);
                        minecraft.getNarrator().sayNow((Component.translatable("narrator.select", entry.account.getAccountStorage().getPlayerName())).getString());
                    });
                });
            }
        } else {
            super.setSelected(null);
        }
    }

    @Override
    protected int getScrollbarPosition() {
        return this.getRight();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        AccountEntry entry = this.getSelected();
        return entry != null && entry.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    public class AccountEntry extends Entry<AccountEntry> {
        private static final String ACTION_UP = "↑";

        private static final String ACTION_DELETE = "x";

        private static final String ACTION_DOWN = "↓";

        private final BaseAccount account;

        private ResourceLocation avatarTextureId;

        public AccountEntry(BaseAccount account) {
            this.account = account;
            this.avatarTextureId = loadAvatar(account.getAvatarKey() != null ? AvatarCache.load(account.getAvatarKey()) : null);
            if (this.avatarTextureId == null) this.avatarTextureId = loadAvatar(AvatarCache.loadDefaultAvatar());
        }

        @Override
        public void render(GuiGraphics context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
            context.drawString(minecraft.font, this.account.getAccountStorage().getPlayerName(), x + 32 + 3, y + 1, 0xFFFFFF, false);
            context.drawString(minecraft.font, this.account.getAccountName() == null ? I18N.TRANSLATOR.translate(this.account.getAccountType()) : Component.nullToEmpty(this.account.getAccountName()), x + 32 + 3, y + 1 + 9, 0xFFFFFF, false);
            context.drawString(minecraft.font, I18N.TRANSLATOR.translate(this.account.getAccountStorage().getState()), x + 32 + 3, y + 1 + 18, 0xFFFFFF, false);
            context.blit(avatarTextureId, x, y, 1, 1, 30, 30, 32, 32);

            if (this.account.getAccountType() != AccountType.ENV_DEFAULT) {
                if (index > 1) {
                    context.drawString(minecraft.font, ACTION_UP, (int) (x + entryWidth - 1.5 * minecraft.font.width(ACTION_UP)), y + 1 + 5 - minecraft.font.lineHeight / 2, 0xFFFFFF, false);
                }

                context.drawString(minecraft.font, ACTION_DELETE, (int) (x + entryWidth - 1.5 * minecraft.font.width(ACTION_DELETE)), y + 1 + 15 - minecraft.font.lineHeight / 2, 0xFFFFFF, false);

                if (index < getItemCount() - 1) {
                    context.drawString(minecraft.font, ACTION_DOWN, (int) (x + entryWidth - 1.5 * minecraft.font.width(ACTION_DOWN)), y + 1 + 25 - minecraft.font.lineHeight / 2, 0xFFFFFF, false);
                }
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            int right = getRowRight();
            int buttonW = minecraft.font.width("x");
            if (mouseX >= right - buttonW * 1.5 && mouseX <= right - buttonW * 0.5) {
                int index = children().indexOf(this);
                int top = getRowTop(index);

                if (this.account.getAccountType() != AccountType.ENV_DEFAULT) {
                    if (index > 1) {
                        int btnTop = top + 1 + 5 - minecraft.font.lineHeight / 2;
                        if (mouseY >= btnTop && mouseY <= btnTop + minecraft.font.lineHeight) {
                            AccountManager.moveAccount(this.account, index - 1);
                            AccountListWidget.this.syncAccounts();
                            return false;
                        }
                    }

                    int btnTop = top + 1 + 15 - minecraft.font.lineHeight / 2;
                    if (mouseY >= btnTop && mouseY <= btnTop + minecraft.font.lineHeight) {
                        AccountManager.dropAccount(this.account);
                        AccountListWidget.this.syncAccounts();
                        return false;
                    }

                    if (index < getItemCount() - 1) {
                        int btnTop2 = top + 1 + 25 - minecraft.font.lineHeight / 2;
                        if (mouseY >= btnTop2 && mouseY <= btnTop2 + minecraft.font.lineHeight) {
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
        public @NotNull Component getNarration() {
            return Component.nullToEmpty("");
        }

        private ResourceLocation loadAvatar(byte[] imageBytes) {
            try {
                if (imageBytes == null || imageBytes.length == 0)
                    return null;


                NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(imageBytes));
                DynamicTexture texture = new DynamicTexture(nativeImage);

                AccountType type = account.getAccountType();
                UUID uuid = account.getAccountStorage().getPlayerUUID();
                String server = type == AccountType.AUTHLIB_INJECTOR || type == AccountType.UNITED_INJECTOR ?
                        ((AbstractInjectorAccount) account).getServer() : "";
                String textureKey = type.toString() + "_" + uuid.toString() + "_" + server;
                UUID textureUUID = UUID.nameUUIDFromBytes(textureKey.getBytes(StandardCharsets.UTF_8));

                return minecraft.getTextureManager().register(
                        "accountsx_avatar_" + textureUUID,
                        texture
                );
            } catch (IOException e) {
                LOGGER.error("Failed to load avatar for {}: {}", account.getAccountStorage().getPlayerName(), e.getMessage());
                return null;
            }
        }
    }
}
