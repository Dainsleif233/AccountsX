package top.syshub.accountsx.authlib;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.minecraft.TelemetrySession;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.util.UUIDTypeAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.authlib.services.*;
import com.mojang.authlib.services.request.AbuseReportRequest;
import com.mojang.authlib.services.response.KeyPairResponse;
import com.mojang.authlib.services.response.discovery.*;
import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.mojang.authlib.minecraft.report.AbuseReportLimits;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.microsoft.MicrosoftConstants;
import top.syshub.accountsx.common.accounts.model.context.AccountContext;
import top.syshub.accountsx.common.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.common.accounts.model.context.AuthServerContext;
import top.syshub.accountsx.common.adapters.api.AuthlibBridge;
import top.syshub.accountsx.common.utils.UnsafeVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * authlib 10.0.77：YggdrasilAuthenticationService 已删除，改为
 * {@link MinecraftServicesDiscoveryService} + 合成 {@link DiscoveryResponse}，
 * 以兼容 authlib-injector 的固定路径 Yggdrasil 端点。
 */
public final class AuthlibAdapterImpl implements AuthlibBridge<AccountSessionImpl> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthlibAdapterImpl.class);

    @Override
    public AccountSessionImpl createAccountProfile(BaseAccount.AccountStorage storage, AccountContext context, Proxy proxy) throws IOException {
        if (context == null) {
            // 环境账号（启动器会话）与离线账号没有 injector 上下文，但它们用的仍是 Mojang 的服务：
            // 用 createOffline 会合成一个"没有任何端点"的 discovery，之后所有 getUrl 都抛
            // "Services are unavailable"，切回默认账号就会因为查不到档案而失败。
            // 与 ≤26.2 的 new YggdrasilAuthenticationService(proxy) 保持一致：走官方端点。
            MinecraftServicesDiscoveryService discovery = MinecraftServicesDiscoveryService.create(proxy);
            SessionService sessionService = discovery.createMinecraftSessionService();
            return new AccountSessionImpl(
                    storage, discovery, sessionService,
                    UserApiService.OFFLINE_PROPERTIES, UserApiService.OFFLINE,
                    computeProfile(storage, sessionService)
            );
        }

        AuthServerContext server = context.server();
        DiscoveryResponse discoveryResponse = buildDiscovery(server);
        MinecraftServicesDiscoveryService discovery = ofDiscoveryService(proxy, discoveryResponse);
        SessionService sessionService = ofSessionService(proxy, discovery, context.security());

        UserApiService userAPIService = switch (context.policy()) {
            case ONLINE -> createUserApiService(discovery, proxy, storage.getAccessToken(), server);
            case OFFLINE -> UserApiService.OFFLINE;
            case TRY -> {
                try {
                    yield createUserApiService(discovery, proxy, storage.getAccessToken(), server);
                } catch (Exception e) {
                    yield UserApiService.OFFLINE;
                }
            }
        };

        return new AccountSessionImpl(storage, discovery, sessionService, switch (context.policy()) {
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
                } catch (Exception e) {
                    yield UserApiService.OFFLINE_PROPERTIES;
                }
            }
        }, userAPIService, computeProfile(storage, sessionService));
    }

    private static boolean isMojangProd(AuthServerContext server) {
        return "PROD".equals(server.name())
                && MicrosoftConstants.SERVICES.equals(server.serviceURL());
    }

    private static UserApiService createUserApiService(MinecraftServicesDiscoveryService discovery, Proxy proxy,
                                                       String accessToken, AuthServerContext server) {
        if (isMojangProd(server)) {
            return discovery.createUserApiService(accessToken);
        }
        return new LenientUserApiService(accessToken, discovery, proxy, server.profileKeySupported());
    }

    // ── 合成 Discovery：把 AuthServerContext 的固定路径映射到 authlib 10 的端点名 ──

    private static DiscoveryResponse buildDiscovery(AuthServerContext server) {
        String session = trimSlash(server.sessionURL());
        String services = trimSlash(server.serviceURL());
        String accounts = trimSlash(server.accountURL());

        return new DiscoveryResponse(
                server.name(),
                "minecraft",
                new Discovery(
                        "minecraft",
                        new Endpoints(Map.of(
                                "getPublicKeys", new Endpoint(services + "/publickeys")
                        )),
                        new Endpoints(Map.of(
                                "join", new Endpoint(session + "/session/minecraft/join"),
                                "verify", new Endpoint(session + "/session/minecraft/hasJoined"),
                                "getProfileById", new Endpoint(session + "/session/minecraft/profile/{profileId}")
                        )),
                        new Endpoints(Map.of(
                                "getCertificates", new Endpoint(services + "/player/certificates"),
                                "getBlocklist", new Endpoint(services + "/privacy/blocklist"),
                                "getAttributes", new Endpoint(services + "/player/attributes"),
                                "sendReport", new Endpoint(services + "/player/report"),
                                "getFriends", new Endpoint(services + "/friends"),
                                "updateFriends", new Endpoint(services + "/friends"),
                                "updateAttributes", new Endpoint(services + "/player/attributes"),
                                "updatePresence", new Endpoint(services + "/presence")
                        )),
                        new Endpoints(Map.of(
                                "getManyByName", new Endpoint(accounts + "/profiles/minecraft"),
                                "getByName", new Endpoint(accounts + "/profiles/minecraft/{name}"),
                                "getTexture", new Endpoint(
                                        "https://textures.minecraft.net/texture/{textureId}",
                                        List.of("https://textures.minecraft.net/texture/")
                                )
                        )),
                        Endpoints.empty()
                )
        );
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final MethodHandle MSDS_CTOR = UnsafeVM.prepareMH(
            "MinecraftServicesDiscoveryService::new",
            lookup -> lookup.findConstructor(
                    MinecraftServicesDiscoveryService.class,
                    MethodType.methodType(void.class, Proxy.class, boolean.class, Supplier.class)
            )
    );

    private static MinecraftServicesDiscoveryService ofDiscoveryService(Proxy proxy, DiscoveryResponse response) {
        try {
            // servicesKeySetEnabled=false：公钥由 AuthSecurityContext 注入，不再走 discovery 联网拉取
            return (MinecraftServicesDiscoveryService) MSDS_CTOR.invoke(proxy, false, (Supplier<DiscoveryResponse>) () -> response);
        } catch (Throwable t) {
            throw UnsafeVM.fail("MinecraftServicesDiscoveryService::new", t);
        }
    }

    /**
     * SessionService 构造器是 protected，子类在任意包均可调用；
     * 借此把 AuthSecurityContext 的公钥装进 ServicesKeySet，替代旧版字段注入。
     */
    static final class PolicySessionService extends MinecraftServicesSessionService {
        private static final Logger LOGGER = LoggerFactory.getLogger(PolicySessionService.class);

        private final AuthSecurityContext security;
        private final Function<Property, SignatureState> signatureStateResolver;
        private final Gson gson = new GsonBuilder().registerTypeAdapter(UUID.class, new UUIDTypeAdapter()).create();

        PolicySessionService(ServicesKeySet keySet, Proxy proxy, MinecraftServicesDiscoveryService discovery, AuthSecurityContext security) {
            this(keySet, proxy, discovery, security, null);
        }

        /**
         * 测试注入点：单测没有 Fabric 运行时，UnsafeVM 取不到 IMPL_LOOKUP，
         * 无法构造指向 authlib 私有方法的 MethodHandle。
         */
        PolicySessionService(ServicesKeySet keySet, Proxy proxy, MinecraftServicesDiscoveryService discovery, AuthSecurityContext security,
                             Function<Property, SignatureState> signatureStateResolver) {
            super(keySet, proxy, discovery);
            this.security = security;
            this.signatureStateResolver = signatureStateResolver == null ? this::authlibSignatureState : signatureStateResolver;
        }

        private SignatureState authlibSignatureState(Property property) {
            try {
                return (SignatureState) SignatureStateAccessor.GET_PROPERTY_SIGNATURE_STATE.invoke(this, property);
            } catch (Throwable t) {
                throw UnsafeVM.fail("MinecraftServicesSessionService::getPropertySignatureState", t);
            }
        }

        @Override
        public MinecraftProfileTextures unpackTextures(Property packedTextures) {
            String value = packedTextures.value();
            SignatureState signatureState = this.signatureStateResolver.apply(packedTextures);

            com.mojang.authlib.services.response.MinecraftTexturesPayload result;
            try {
                String json = new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
                result = this.gson.fromJson(json, com.mojang.authlib.services.response.MinecraftTexturesPayload.class);
            } catch (final JsonParseException | IllegalArgumentException e) {
                LOGGER.error("Could not decode textures payload", e);
                return MinecraftProfileTextures.EMPTY;
            }

            if (result == null || result.textures() == null || result.textures().isEmpty()) {
                return MinecraftProfileTextures.EMPTY;
            }

            for (MinecraftProfileTexture entry : result.textures().values()) {
                String url = entry.getUrl();
                if (security.shouldBlockSkinUrl(url)) {
                    LOGGER.error("Textures payload contains blocked domain: {}", url);
                    return MinecraftProfileTextures.EMPTY;
                }
            }

            java.util.Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = result.textures();

            return new MinecraftProfileTextures(
                    textures.get(MinecraftProfileTexture.Type.SKIN),
                    textures.get(MinecraftProfileTexture.Type.CAPE),
                    textures.get(MinecraftProfileTexture.Type.ELYTRA),
                    signatureState
            );

        }
        /** 延迟到真正用到时才解析 authlib 私有方法，避免单测在类初始化阶段依赖 UnsafeVM。 */
        private static final class SignatureStateAccessor {
            private static final MethodHandle GET_PROPERTY_SIGNATURE_STATE = UnsafeVM.prepareMH(
                    "MinecraftServicesSessionService::getPropertySignatureState", lookup -> lookup.findVirtual(
                            MinecraftServicesSessionService.class,
                            "getPropertySignatureState",
                            MethodType.methodType(SignatureState.class, Property.class)
                    )
            );
        }
    }


    private static SessionService ofSessionService(Proxy proxy, MinecraftServicesDiscoveryService discovery, AuthSecurityContext security) {
        ServicesKeySet keySet = customKeySet(security);
        return new PolicySessionService(keySet, proxy, discovery, security);
    }

    private static ServicesKeySet customKeySet(AuthSecurityContext security) {
        List<ServicesKeyInfo> profilePropertyKeys = DefaultServicesKeyInfo.process(security.profilePropertyKeys());
        List<ServicesKeyInfo> playerCertificateKeys = DefaultServicesKeyInfo.process(security.playerCertificateKeys());
        return type -> switch (type) {
            case PROFILE_PROPERTY -> profilePropertyKeys;
            case PROFILE_KEY -> playerCertificateKeys;
        };
    }

    private static ProfileResult computeProfile(BaseAccount.AccountStorage storage, SessionService sessionService) {
        ProfileResult result = sessionService.fetchProfile(storage.getPlayerUUID(), true);
        if (result != null) {
            return result;
        }
        // 离线账号的 UUID 在服务端本就不存在，查不到是正常结果。这里返回 null 会让
        // Minecraft 的 profileFuture 里塞进 null：档案名/UUID 只能靠 User 兜底，皮肤与
        // 档案动作全部丢失。用本地 storage 合成一份，与 vanilla 的兜底语义一致。
        return new ProfileResult(new GameProfile(storage.getPlayerUUID(), storage.getPlayerName()));
    }

    /**
     * 注入器环境往往没有 player/attributes、privacy/blocklist、player/certificates 等端点。
     * 兼容缺少这些端点的旧服务器；声明支持 profile key 时保留真实证书请求的结果。
     */
    private static final class LenientUserApiService implements UserApiService {
        private final MinecraftServicesUserApiService delegate;
        private final boolean profileKeySupported;

        LenientUserApiService(String accessToken, MinecraftServicesDiscoveryService discovery, Proxy proxy,
                              boolean profileKeySupported) {
            this.delegate = new MinecraftServicesUserApiService(accessToken, discovery, proxy);
            this.profileKeySupported = profileKeySupported;
        }

        @Override
        public UserProperties fetchProperties() throws AuthenticationException {
            try {
                return delegate.fetchProperties();
            } catch (Exception e) {
                return OFFLINE_PROPERTIES;
            }
        }

        @Override
        public boolean isBlockedPlayer(UUID playerID) {
            try {
                return delegate.isBlockedPlayer(playerID);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void refreshBlockList() {
            try {
                delegate.refreshBlockList();
            } catch (Exception ignored) {
            }
        }

        @Override
        public KeyPairResponse getKeyPair() {
            // 声明支持的服务器需要真实签名，失败时保留 authlib 的结果与异常语义。
            if (profileKeySupported) {
                return delegate.getKeyPair();
            }
            try {
                KeyPairResponse response = delegate.getKeyPair();
                if (response != null) {
                    return response;
                }
            } catch (Exception ignored) {
            }
            // authlib 也会用 null 表示请求失败；旧服务器仍需离线密钥对兜底。
            return syntheticKeyPair();
        }

        @Override
        public TelemetrySession newTelemetrySession(java.util.concurrent.Executor executor) {
            return delegate.newTelemetrySession(executor);
        }

        @Override
        public void reportAbuse(AbuseReportRequest request) {
            delegate.reportAbuse(request);
        }

        @Override
        public boolean canSendReports() {
            return false;
        }

        @Override
        public AbuseReportLimits getAbuseReportLimits() {
            return delegate.getAbuseReportLimits();
        }

        private static KeyPairResponse syntheticKeyPair() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();
                Base64.Encoder base64 = Base64.getMimeEncoder(76, "\n".getBytes(UTF_8));
                String privateKey = "-----BEGIN RSA PRIVATE KEY-----\n"
                        + base64.encodeToString(keyPair.getPrivate().getEncoded())
                        + "\n-----END RSA PRIVATE KEY-----\n";
                String publicKey = "-----BEGIN RSA PUBLIC KEY-----\n"
                        + base64.encodeToString(keyPair.getPublic().getEncoded())
                        + "\n-----END RSA PUBLIC KEY-----\n";
                Instant now = Instant.now();
                return new KeyPairResponse(
                        new KeyPairResponse.KeyPair(privateKey, publicKey),
                        StandardCharsets.UTF_8.encode("AA=="),
                        DateTimeFormatter.ISO_INSTANT.format(now.plus(48, ChronoUnit.HOURS)),
                        DateTimeFormatter.ISO_INSTANT.format(now.plus(36, ChronoUnit.HOURS))
                );
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("RSA unavailable", e);
            }
        }
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
