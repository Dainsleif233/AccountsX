package top.syshub.accountsx.adapters.mc.mixins.mixins;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.I18N;
import top.syshub.accountsx.adapters.mc.ui.TitleScreenAccountButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.FriendsButton;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    @Unique
    private static final Identifier SWITCH_ACCOUNT_ICON_TEXTURE = Identifier.fromNamespaceAndPath(AccountsX.MOD_ID, "icon/account");

    @Shadow
    private boolean fading;

    @Shadow
    private long fadeInStart;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    protected void init(CallbackInfo ci) {
        SpriteIconButton button = SpriteIconButton.builder(
                        Component.empty(),
                        (pressed) -> this.minecraft.setScreenAndShow(new AccountScreen(this)),
                        true)
                .size(20, 20)
                .sprite(SWITCH_ACCOUNT_ICON_TEXTURE, 20, 20)
                .tooltip(Component.translatable("accountsx.account.general.account_list"))
                .build();
        // 先排位置再登记控件：登记之后再扫这一行会把刚加的自己也算进去
        this.accountsx$joinIconRow(button);
        this.addRenderableWidget(button);
    }

    // 图标行的纵坐标：好友按钮缺失时拿这一行其它图标（语言 / 无障碍）的作为参照
    @Unique
    private int accountsx$iconRowY() {
        for (GuiEventListener child : this.children()) {
            if (child instanceof SpriteIconButton icon) {
                return icon.getY();
            }
        }
        return -1;
    }

    /**
     * 26.2 起主界面把好友、语言、无障碍排成一行 20x20 的图标，入口进这一行、插在好友左边。
     * 行内横坐标由原版按「第几个 / 共几个」算，多塞一个不重排就会顶出行外；这里按实际排好的
     * 顺序整行重新居中，间距 4 与原版 getHorizontalPosition 一致（那个方法是私有的）。
     * 按实际位置收集而不是按数量推算，装了同样往这行塞按钮的模组（如 ModMenu）也不会重叠。
     * 好友按钮被别的模组摘掉时追加到行末（原版必定创建它，这只是兜底）。
     */
    @Unique
    private void accountsx$joinIconRow(SpriteIconButton button) {
        FriendsButton friends = null;
        for (GuiEventListener child : this.children()) {
            if (child instanceof FriendsButton found) {
                friends = found;
                break;
            }
        }

        // 好友按钮缺失时借同一行其它图标（语言 / 无障碍）的纵坐标，把它们当作行首
        int rowY = friends != null ? friends.getY() : accountsx$iconRowY();

        List<SpriteIconButton> row = new ArrayList<>();
        for (GuiEventListener child : this.children()) {
            if (child instanceof SpriteIconButton icon && icon.getY() == rowY) {
                row.add(icon);
            }
        }
        if (row.isEmpty()) {
            TitleScreenAccountButton.placeBesideMultiplayer(this, button);
            return;
        }

        row.sort(Comparator.comparingInt(SpriteIconButton::getX));
        row.add(friends == null ? row.size() : row.indexOf(friends), button);

        int spacing = 4;
        int startX = this.width / 2 - (row.size() * 24 - spacing) / 2;
        for (int i = 0; i < row.size(); i++) {
            row.get(i).setPosition(startX + i * 24, rowY);
        }
    }

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    public void onExtractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        float f = this.fading ? (float) (Util.getMillis() - this.fadeInStart) / 1000.0F : 1.0F;
        float g = this.fading ? Mth.clamp(f - 1.0F, 0.0F, 1.0F) : 1.0F;
        int i = Mth.ceil(g * 255.0F) << 24;

        if ((i & -67108864) != 0) {
            graphics.centeredText(this.font, I18N.currentAccount(), this.width / 2, 15, 0xFFFFFF | i);
        }
    }
}