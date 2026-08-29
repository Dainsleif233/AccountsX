package top.syshub.accountsx.authlib;

import com.mojang.authlib.Environment;
import com.mojang.authlib.HttpAuthenticationService;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.*;
import top.syshub.accountsx.core.accounts.BaseAccount;
import top.syshub.accountsx.core.accounts.impl.microsoft.MicrosoftConstants;
import top.syshub.accountsx.core.accounts.model.context.AccountContext;
import top.syshub.accountsx.core.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.core.adapters.api.AuthlibBridge;
import top.syshub.accountsx.core.utils.UnsafeVM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.net.Proxy;
import java.security.*;
import java.util.Base64;
import java.util.List;

public final class AuthlibAdapterImpl implements AuthlibBridge<AccountSessionImpl> {

    // 1.1 修复：原先在 static 块里做网络 I/O，联网失败（断网/被墙/Mojang 抖动）会抛
    // ExceptionInInitializerError 导致整个模组不可用（连离线账号都用不了）。改为懒加载 +
    // 失败降级为仅 Mojang 默认白名单（AuthSecurityContext 默认构造使用 MOJANG_DEFAULT）。
    private static volatile AuthSecurityContext selectedSecurityField;

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthlibAdapterImpl.class);

    /** 懒加载并 memoize 的默认皮肤安全上下文；首次访问时才联网获取 Microsoft 公钥。 */
    public static AuthSecurityContext selectedSecurity() {
        AuthSecurityContext ctx = selectedSecurityField;
        if (ctx == null) {
            synchronized (AuthlibAdapterImpl.class) {
                ctx = selectedSecurityField;
                if (ctx == null) {
                    selectedSecurityField = ctx = computeDefaultSecurity();
                }
            }
        }
        return ctx;
    }

    private static AuthSecurityContext computeDefaultSecurity() {
        try {
            return MicrosoftConstants.computeMicrosoftPublicKeys();
        } catch (IOException e) {
            LOGGER.warn("无法获取 Microsoft 公钥，降级为仅 Mojang 默认皮肤白名单。", e);
            return new AuthSecurityContext(List.of(), List.of());
        }
    }

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
            Environment env = new Environment(
                    context.server().sessionURL(),
                    context.server().serviceURL(),
                    context.server().accountURL(),
                    context.server().name()
            );
            YggdrasilAuthenticationService service = ofYggdrasilAuthenticationService(proxy, env, context.security());
            MinecraftSessionService sessionService = new YggdrasilMinecraftSessionService(service.getServicesKeySet(), service.getProxy(), env) {};
            // 登录时用该账号自身的安全上下文覆盖默认（2.3 竞态留待 P4）
            selectedSecurityField = context.security();

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

        private static final Logger LOGGER = LoggerFactory.getLogger(ServicesKeyInfo.class);

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
