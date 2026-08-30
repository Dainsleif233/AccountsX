package top.syshub.accountsx.authlib;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.Environment;
import com.mojang.authlib.HttpAuthenticationService;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.*;
import com.mojang.authlib.yggdrasil.response.MinecraftTexturesPayload;
import com.mojang.util.UUIDTypeAdapter;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.model.context.AccountContext;
import top.syshub.accountsx.core.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.core.adapters.api.AuthlibBridge;
import top.syshub.accountsx.core.utils.UnsafeVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AuthlibAdapterImpl implements AuthlibBridge<AccountSessionImpl> {
    @Override
    public AccountSessionImpl createAccountProfile(BaseAccount.AccountStorage storage, AccountContext context, Proxy proxy) throws IOException {
        if (context == null) {
            YggdrasilAuthenticationService service = new YggdrasilAuthenticationService(proxy);
            MinecraftSessionService sessionService = service.createMinecraftSessionService();

            return new AccountSessionImpl(
                    storage, service, sessionService,
                    UserApiService.OFFLINE_PROPERTIES, UserApiService.OFFLINE, computeProfile(storage, sessionService)
            );
        } else {
            Environment env = new Environment(context.server().sessionURL(), context.server().serviceURL(), context.server().name());
            YggdrasilAuthenticationService service = ofYggdrasilAuthenticationService(proxy, env, context.security());

            MinecraftSessionService sessionService = new YggdrasilMinecraftSessionService(service.getServicesKeySet(), service.getProxy(), env) {
                private static final Logger LOGGER = LoggerFactory.getLogger(AuthlibAdapterImpl.class);

                private static final MethodHandle GET_PROPERTY_SIGNATURE_STATE = UnsafeVM.prepareMH(
                        "YggdrasilMinecraftSessionService::getPropertySignatureState", lookup -> lookup.findVirtual(
                                YggdrasilMinecraftSessionService.class,
                                "getPropertySignatureState",
                                MethodType.methodType(SignatureState.class, Property.class)
                        )
                );

                private final Gson gson = new GsonBuilder().registerTypeAdapter(UUID.class, new UUIDTypeAdapter()).create();

                @Override
                public MinecraftProfileTextures unpackTextures(Property packedTextures) {
                    String value = packedTextures.value();
                    SignatureState signatureState;
                    try {
                        signatureState = (SignatureState) GET_PROPERTY_SIGNATURE_STATE.invoke((YggdrasilMinecraftSessionService) this, packedTextures);
                    } catch (Throwable t) {
                        throw UnsafeVM.fail("YggdrasilMinecraftSessionService::getPropertySignatureState", t);
                    }

                    MinecraftTexturesPayload result;
                    try {
                        String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                        result = this.gson.fromJson(json, MinecraftTexturesPayload.class);
                    } catch (final JsonParseException | IllegalArgumentException e) {
                        LOGGER.error("Could not decode textures payload", e);
                        return MinecraftProfileTextures.EMPTY;
                    }

                    if (result == null || result.textures() == null || result.textures().isEmpty()) {
                        return MinecraftProfileTextures.EMPTY;
                    }

                    for (MinecraftProfileTexture entry : result.textures().values()) {
                        String url = entry.getUrl();
                        if (context.security().shouldBlockSkinUrl(url)) {
                            LOGGER.error("Textures payload contains blocked domain: {}", url);
                            return MinecraftProfileTextures.EMPTY;
                        }
                    }

                    Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = result.textures();

                    return new MinecraftProfileTextures(
                            textures.get(MinecraftProfileTexture.Type.SKIN),
                            textures.get(MinecraftProfileTexture.Type.CAPE),
                            textures.get(MinecraftProfileTexture.Type.ELYTRA),
                            signatureState
                    );
                }
            };

            UserApiService userAPIService = switch (context.policy()) {
                case ONLINE -> service.createUserApiService(storage.getAccessToken());
                case OFFLINE -> UserApiService.OFFLINE;
                case TRY -> {
                    try {
                        yield service.createUserApiService(storage.getAccessToken());
                    } catch (Exception e) {
                        yield UserApiService.OFFLINE;
                    }
                }
            };
            return new AccountSessionImpl(storage, service, sessionService, switch (context.policy()) {
                case ONLINE -> {
                    try {
                        yield userAPIService.fetchProperties();
                    } catch (AuthenticationException e) {
                        throw new IOException(e);
                    }
                }
                case OFFLINE -> UserApiService.OFFLINE_PROPERTIES;
                case TRY -> {
                    try {
                        yield userAPIService.fetchProperties();
                    } catch (AuthenticationException e) {
                        yield UserApiService.OFFLINE_PROPERTIES;
                    }
                }
            }, userAPIService, computeProfile(storage, sessionService));
        }
    }

    private static final MethodHandle YASA_AL = UnsafeVM.getClassAllocator(YggdrasilAuthenticationService.class);

    private static final MethodHandle YASA_S_PROXY = UnsafeVM.prepareMH(
            "HttpAuthenticationService.proxy", lookup -> lookup.findSetter(HttpAuthenticationService.class, "proxy", Proxy.class)
    );

    private static final MethodHandle YASA_S_ENV = UnsafeVM.prepareMH(
            "YggdrasilAuthenticationService.environment", lookup -> lookup.findSetter(YggdrasilAuthenticationService.class, "environment", Environment.class)
    );

    private static final MethodHandle YASA_S_KS = UnsafeVM.prepareMH(
            "YggdrasilAuthenticationService.servicesKeySet", lookup -> lookup.findSetter(YggdrasilAuthenticationService.class, "servicesKeySet", ServicesKeySet.class)
    );

    private static YggdrasilAuthenticationService ofYggdrasilAuthenticationService(Proxy proxy, Environment env, AuthSecurityContext securityContext) {
        try {
            YggdrasilAuthenticationService service = (YggdrasilAuthenticationService) YASA_AL.invoke();
            YASA_S_PROXY.invoke(service, proxy);
            YASA_S_ENV.invoke(service, env);
            List<ServicesKeyInfo> profilePropertyKeys = DefaultServicesKeyInfo.process(securityContext.profilePropertyKeys());
            List<ServicesKeyInfo> playerCertificateKeys = DefaultServicesKeyInfo.process(securityContext.playerCertificateKeys());
            YASA_S_KS.invoke(service, (ServicesKeySet) type -> switch (type) {
                case PROFILE_PROPERTY -> profilePropertyKeys;
                case PROFILE_KEY -> playerCertificateKeys;
            });

            return service;
        } catch (Throwable t) {
            throw UnsafeVM.fail("YggdrasilAuthenticationService::new", t);
        }
    }

    private ProfileResult computeProfile(BaseAccount.AccountStorage storage, MinecraftSessionService sessionService) {
        return sessionService.fetchProfile(storage.getPlayerUUID(), true);
    }

    private record DefaultServicesKeyInfo(PublicKey publicKey) implements ServicesKeyInfo {
        public static List<ServicesKeyInfo> process(List<PublicKey> publicKeys) {
            return publicKeys.stream().<ServicesKeyInfo>map(DefaultServicesKeyInfo::new).toList();
        }

        private static final Logger LOGGER = LoggerFactory.getLogger(DefaultServicesKeyInfo.class);

        @Override
        public int keyBitCount() {
            return 4096;
        }

        @Override
        public Signature signature() {
            try {
                final Signature signature = Signature.getInstance("SHA1withRSA");
                signature.initVerify(publicKey);
                return signature;
            } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
                throw new AssertionError("Failed to create signature", e);
            }
        }

        @Override
        public boolean validateProperty(Property property) {
            final Signature signature = signature();
            final byte[] expected;
            try {
                expected = Base64.getDecoder().decode(property.signature());
            } catch (final IllegalArgumentException e) {
                LOGGER.error("Malformed signature encoding on property {}", property, e);
                return false;
            }
            try {
                signature.update(property.value().getBytes());
                return signature.verify(expected);
            } catch (final SignatureException e) {
                LOGGER.error("Failed to verify signature on property {}", property, e);
            }
            return false;
        }
    }
}
