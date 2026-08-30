package top.syshub.accountsx.adapters.mc.mixins.mixins;

import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.I18N;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.SpriteIconButton;
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

    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/CommonButtons;language(ILnet/minecraft/client/gui/components/Button$OnPress;Z)Lnet/minecraft/client/gui/components/SpriteIconButton;"))
    protected void init(CallbackInfo ci) {
        this.addRenderableWidget(SpriteIconButton.builder(
                        Component.empty(),
                        (button) -> this.minecraft.setScreen(new AccountScreen(this)),
                        true)
                .size(20, 20)
                .sprite(SWITCH_ACCOUNT_ICON_TEXTURE, 20, 20)
                .build()
        ).setPosition(this.width / 2 + 104, this.height / 4 + 72);
    }

    @Inject(method = "render", at = @At("RETURN"))
    public void onRender(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        float f = this.fading ? (float) (Util.getMillis() - this.fadeInStart) / 1000.0F : 1.0F;
        float g = this.fading ? Mth.clamp(f - 1.0F, 0.0F, 1.0F) : 1.0F;
        int i = Mth.ceil(g * 255.0F) << 24;

        if ((i & -67108864) != 0) {
            context.drawCenteredString(this.font, I18N.TRANSLATOR.translate(AccountManager.getCurrentAccount()), this.width / 2, 15, 0xFFFFFF | i);
        }
    }
}
