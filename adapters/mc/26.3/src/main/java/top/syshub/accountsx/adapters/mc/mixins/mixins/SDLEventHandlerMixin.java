package top.syshub.accountsx.adapters.mc.mixins.mixins;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.SDLEventHandler;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.syshub.accountsx.common.auth.server.TextDropHandler;

/**
 * 截 SDL 的文本拖拽事件（SDL_EVENT_DROP_TEXT）。
 *
 * <p>SDL3 原生层会把拖入的文本投递成 DROP_TEXT，但原版 {@code SDLEventHandler.pollEvents} 的
 * switch 只处理 DROP_FILE，文本落到 default 被丢弃。这里在事件分派处补上文本一路：任意界面拖入
 * 都能收到，且只读不改——DROP_FILE 仍走原版路径，资源包 / Iris 的文件拖拽不受影响。</p>
 *
 * <p>仅 26.3+（SDL 窗口层）有效；GLFW 版本原生层只投递文件，够不到文本。</p>
 */
@Mixin(SDLEventHandler.class)
public class SDLEventHandlerMixin {

    @Inject(method = "pollEvents", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDL_Event;type()I"))
    private void accountsx$onTextDrop(CallbackInfo ci, @Local SDL_Event event) {
        if (event.type() == SDLEvents.SDL_EVENT_DROP_TEXT) {
            TextDropHandler.handle(event.drop().dataString());
        }
    }
}
