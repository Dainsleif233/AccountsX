package top.syshub.accountsx.adapters.mc.mixins.mixins;

import com.mojang.authlib.yggdrasil.TextureUrlChecker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static top.syshub.accountsx.authlib.AuthlibAdapterImpl.selectedSecurity;

@Mixin(value = TextureUrlChecker.class, remap = false)
public abstract class TextureUrlCheckerMixin {

    @Inject(
            method = "isAllowedTextureDomain",
            at = @At("HEAD"),
            remap = false,
            cancellable = true)
    private static void checkUrl(String url, CallbackInfoReturnable<Boolean> cir) {
        if (!selectedSecurity().shouldBlockSkinUrl(url)) cir.setReturnValue(true);
    }
}