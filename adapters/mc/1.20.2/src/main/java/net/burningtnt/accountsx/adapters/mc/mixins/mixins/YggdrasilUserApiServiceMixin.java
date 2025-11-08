package net.burningtnt.accountsx.adapters.mc.mixins.mixins;

import com.mojang.authlib.Environment;
import com.mojang.authlib.yggdrasil.YggdrasilUserApiService;
import com.mojang.authlib.yggdrasil.response.KeyPairResponse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

@Mixin(value = YggdrasilUserApiService.class, remap = false)
public class YggdrasilUserApiServiceMixin {

    @Final
    @Shadow
    private Environment environment;

    @Unique
    private boolean isAuthlibInjector() {
        return environment.name().equals("Authlib-Injector");
    }

    @Inject(
            method = "fetchProperties",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void defaultAttributes(CallbackInfo ci) {
        if (isAuthlibInjector())
            ci.cancel();
    }

    @Inject(
            method = "isBlockedPlayer",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void notBlockedPlayer(UUID playerID, CallbackInfoReturnable<Boolean> cir) {
        if (isAuthlibInjector()) cir.setReturnValue(false);
    }

    @Inject(
            method = "refreshBlockList",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void blankBlockList(CallbackInfo ci) {
        if (isAuthlibInjector()) ci.cancel();
    }

    @Inject(
            method = "getKeyPair",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void getKeyPair(CallbackInfoReturnable<KeyPairResponse> cir) {
        if (isAuthlibInjector()) {
            KeyPairGenerator generator;
            try {
                generator = KeyPairGenerator.getInstance("RSA");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            Base64.Encoder base64 = Base64.getMimeEncoder(76, "\n".getBytes(UTF_8));
            String privateKey = "-----BEGIN RSA PRIVATE KEY-----\n" + base64.encodeToString(keyPair.getPrivate().getEncoded()) + "\n-----END RSA PRIVATE KEY-----\n";
            String publicKey = "-----BEGIN RSA PUBLIC KEY-----\n" + base64.encodeToString(keyPair.getPublic().getEncoded()) + "\n-----END RSA PUBLIC KEY-----\n";

            Instant now = Instant.now();
            Instant expiresAt = now.plus(48, ChronoUnit.HOURS);
            Instant refreshedAfter = now.plus(36, ChronoUnit.HOURS);

            cir.setReturnValue(new KeyPairResponse(
                    new KeyPairResponse.KeyPair(
                            privateKey,
                            publicKey
                    ),
                    StandardCharsets.UTF_8.encode("AA=="),
                    DateTimeFormatter.ISO_INSTANT.format(expiresAt),
                    DateTimeFormatter.ISO_INSTANT.format(refreshedAfter)
            ));
        }
    }
}