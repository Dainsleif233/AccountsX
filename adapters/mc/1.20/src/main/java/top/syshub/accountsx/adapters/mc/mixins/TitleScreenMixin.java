package top.syshub.accountsx.adapters.mc.mixins;

import top.syshub.accountsx.adapters.mc.ui.AccountScreen;
import top.syshub.accountsx.adapters.mc.ui.I18N;
import top.syshub.accountsx.common.AccountsX;
import top.syshub.accountsx.common.manager.AccountManager;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {
    @Unique
    private static final ResourceLocation SWITCH_ACCOUNT_ICON_TEXTURE = new ResourceLocation(AccountsX.MOD_ID, "textures/gui/account.png");

    @Final
    @Shadow
    private boolean fading;

    @Shadow
    private long fadeInStart;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "createNormalMenuOptions", at = @At("RETURN"))
    protected void init(CallbackInfo ci) {
        // 将 minecraft 实例捕获到局部变量：既保证按钮 OnPress 回调执行时引用非空，
        // 也让静态分析能推断出 setScreen 调用不会产生 NullPointerException。
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }
        this.addRenderableWidget((AbstractWidget) new ImageButton(
                this.width / 2 + 104, this.height / 4 + 72, 20, 20, 0, 0, 20, SWITCH_ACCOUNT_ICON_TEXTURE, 32, 64,
                (buttonWidget) -> minecraft.setScreen(new AccountScreen(this)), Component.translatable("accountsx.account.general.add_account")
        ));
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
