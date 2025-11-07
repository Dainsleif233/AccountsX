package net.burningtnt.accountsx.authlib;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.burningtnt.accountsx.core.accounts.BaseAccount;
import net.burningtnt.accountsx.core.adapters.api.AccountSession;

public record AccountSessionImpl(
        BaseAccount.AccountStorage storage,
        YggdrasilAuthenticationService authenticationService,
        MinecraftSessionService sessionService,
        UserApiService.UserProperties properties,
        UserApiService userAPIService,
        ProfileResult profileResult
) implements AccountSession {
}
