package top.syshub.accountsx.adapters.mc.ui;

import org.jspecify.annotations.NonNull;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.accounts.BaseAccount;

import top.syshub.accountsx.common.manager.AccountManager;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import static top.syshub.accountsx.common.AccountsX.LOGGER;

import com.mojang.blaze3d.platform.NativeImage;
import top.syshub.accountsx.common.utils.image.AvatarCache;

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

    private final top.syshub.accountsx.common.ui.list.AccountListModel model = new top.syshub.accountsx.common.ui.list.DefaultAccountListModel();

    private java.util.function.Predicate<BaseAccount> filter = account -> true;

    public void setFilter(java.util.function.Predicate<BaseAccount> filter) {
        this.filter = filter;
        this.syncAccounts();
    }

    /** 是否允许调整顺序；筛选视图下隐藏上下箭头，避免打乱持久化顺序。 */
    public void setReorderable(boolean reorderable) {
        this.model.setReorderable(reorderable);
    }

    public void syncAccounts() {
        this.clearEntries();
        for (top.syshub.accountsx.common.ui.list.AccountRow row : model.rows()) {
            BaseAccount account = row.account();

            if (!this.filter.test(account)) {
                continue;
            }

            AccountEntry entry = new AccountEntry(account);
            this.addEntry(entry);

            if (account == AccountManager.getCurrentAccount()) {
                super.setSelected(entry);
            }
        }
    }

    @Override
    public void setSelected(@Nullable AccountListWidget.AccountEntry entry) {
        if (entry == null) {
            super.setSelected(null);
            return;
        }

        this.model.onSelect(entry.account, () -> {
            super.setSelected(entry);
            minecraft.getNarrator().saySystemChatQueued(Component.nullToEmpty((Component.translatable("narrator.select", entry.account.getAccountStorage().getPlayerName())).getString()));
        });
    }

    @Override
    protected int scrollBarX() {
        return this.getRight();
    }

    @Override
    public boolean keyPressed(@NonNull KeyEvent input) {
        AccountEntry entry = this.getSelected();
        return entry != null && entry.keyPressed(input) || super.keyPressed(input);
    }

    public class AccountEntry extends Entry<AccountEntry> {
        private static final String ACTION_UP = "↑";

        private static final String ACTION_DELETE = "x";

        private static final String ACTION_DOWN = "↓";

        private final BaseAccount account;

        private Identifier avatarTextureId;

        public AccountEntry(BaseAccount account) {
            this.account = account;
            this.avatarTextureId = loadAvatar(account.getAvatarKey() != null ? AvatarCache.load(account.getAvatarKey()) : null);
            if (this.avatarTextureId == null) this.avatarTextureId = loadAvatar(AvatarCache.loadDefaultAvatar());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
            int index = children().indexOf(this);
            int x = getRowLeft() + 2;
            int y = getRowTop(index) + 2;
            int entryWidth = getRowWidth();

            context.text(minecraft.font, this.account.getAccountStorage().getPlayerName(), x + 32 + 3, y + 1, 0xFFFFFFFF, false);
            context.text(minecraft.font, I18N.accountSource(this.account), x + 32 + 3, y + 1 + 9, 0xFFFFFFFF, false);
            context.text(minecraft.font, I18N.TRANSLATOR.translate(this.account.getAccountStorage().getState()), x + 32 + 3, y + 1 + 18, 0xFFFFFFFF, false);
            context.blit(RenderPipelines.GUI_TEXTURED, avatarTextureId, x, y, 1, 1, 30, 30, 32, 32);

            if (model.isRemovable(this.account)) {
                if (model.canMoveUp(this.account)) {
                    context.text(minecraft.font, ACTION_UP, (int) (x + entryWidth - 1.5 * minecraft.font.width(ACTION_UP)), y + 1 + 5 - minecraft.font.lineHeight / 2, 0xFFFFFFFF, false);
                }

                context.text(minecraft.font, ACTION_DELETE, (int) (x + entryWidth - 1.5 * minecraft.font.width(ACTION_DELETE)), y + 1 + 15 - minecraft.font.lineHeight / 2, 0xFFFFFFFF, false);

                if (model.canMoveDown(this.account)) {
                    context.text(minecraft.font, ACTION_DOWN, (int) (x + entryWidth - 1.5 * minecraft.font.width(ACTION_DOWN)), y + 1 + 25 - minecraft.font.lineHeight / 2, 0xFFFFFFFF, false);
                }
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            double mouseX = click.x();
            double mouseY = click.y();
            int right = getRowRight();
            int buttonW = minecraft.font.width("x");
            if (mouseX >= right - buttonW * 1.5 && mouseX <= right - buttonW * 0.5) {
                int index = children().indexOf(this);
                int top = getRowTop(index);

                if (model.isRemovable(this.account)) {
                    if (model.canMoveUp(this.account)) {
                        int btnTop = top + 1 + 5 - minecraft.font.lineHeight / 2;
                        if (mouseY >= btnTop && mouseY <= btnTop + minecraft.font.lineHeight) {
                            model.onMove(this.account, -1);
                            AccountListWidget.this.syncAccounts();
                            return false;
                        }
                    }

                    int btnTop = top + 1 + 15 - minecraft.font.lineHeight / 2;
                    if (mouseY >= btnTop && mouseY <= btnTop + minecraft.font.lineHeight) {
                        model.onRemove(this.account);
                        AccountListWidget.this.syncAccounts();
                        return false;
                    }

                    if (model.canMoveDown(this.account)) {
                        int btnTop2 = top + 1 + 25 - minecraft.font.lineHeight / 2;
                        if (mouseY >= btnTop2 && mouseY <= btnTop2 + minecraft.font.lineHeight) {
                            model.onMove(this.account, 1);
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
        public @NonNull Component getNarration() {
            return Component.nullToEmpty("");
        }

        private Identifier loadAvatar(byte[] imageBytes) {
            try {
                if (imageBytes == null || imageBytes.length == 0)
                    return null;


                NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(imageBytes));

                AccountType type = account.getAccountType();
                UUID uuid = account.getAccountStorage().getPlayerUUID();
                String server = type == AccountType.AUTHLIB_INJECTOR ?
                        ((AbstractInjectorAccount) account).getServer() : "";
                String textureKey = type.toString() + "_" + uuid.toString() + "_" + server;
                UUID textureUUID = UUID.nameUUIDFromBytes(textureKey.getBytes(StandardCharsets.UTF_8));

                DynamicTexture texture = new DynamicTexture(
                        () -> "accountsx_avatar_" + textureUUID,
                        nativeImage
                );

                Identifier identifier = Identifier.fromNamespaceAndPath("accountsx", "avatar_" + textureUUID);
                minecraft.getTextureManager().register(identifier, texture);
                return identifier;
            } catch (IOException e) {
                LOGGER.error("Failed to load avatar for {}: {}", account.getAccountStorage().getPlayerName(), e.getMessage());
                return null;
            }
        }
    }
}
