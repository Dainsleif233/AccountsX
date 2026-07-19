package top.syshub.accountsx.authlib;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.adapters.api.AccountSession;

public record AccountSessionImpl(
        BaseAccount.AccountStorage storage,
        YggdrasilAuthenticationService authenticationService,
        MinecraftSessionService sessionService,
        UserApiService userAPIService,
        GameProfile gameProfile
) implements AccountSession {
}
