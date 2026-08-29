package top.syshub.accountsx.core.adapters;

import com.google.common.base.Suppliers;
import top.syshub.accountsx.core.AccountsX;
import top.syshub.accountsx.core.adapters.api.AccountSession;
import top.syshub.accountsx.core.adapters.api.AuthlibBridge;
import top.syshub.accountsx.core.adapters.api.MinecraftPlatform;
import top.syshub.accountsx.core.net.HttpGateway;
import top.syshub.accountsx.core.net.JdkHttpGateway;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.function.Supplier;

public final class Platforms {
    private Platforms() {
    }

    private record PlatformImpl(AuthlibBridge<AccountSession> authlibBridge,
                                MinecraftPlatform<AccountSession> minecraftPlatform) {
        public PlatformImpl {
            // Both adapters must declare the generic interface directly.
            // getGenericInterfaces() only returns directly declared interfaces, not inherited ones.
            if (!Arrays.equals(
                    getAccountSessionType(authlibBridge, AuthlibBridge.class),
                    getAccountSessionType(minecraftPlatform, MinecraftPlatform.class)
            )) {
                throw new IllegalStateException("Unmatched adapters!");
            }
        }

        private static Type[] getAccountSessionType(Object o, Class<?> apiClass) {
            Type[] adapterTypes = o.getClass().getGenericInterfaces();
            for (Type adapterType : adapterTypes) {
                if (adapterType == apiClass) {
                    throw new IllegalStateException(String.format("%s should directly implement %s and provide a generic argument.", o.getClass(), apiClass));
                }

                if (adapterType instanceof ParameterizedType pAdapterType && pAdapterType.getRawType() == apiClass) {
                    return pAdapterType.getActualTypeArguments();
                }
            }

            throw new IllegalStateException(String.format("%s should directly implement %s.", o.getClass(), apiClass));
        }
    }

    @SuppressWarnings({"unchecked"})
    private static final Supplier<PlatformImpl> INSTANCE = Suppliers.memoize(() -> {
        try {
            return new PlatformImpl(
                    compute0(AccountsX.AUTHLIB_ADAPTER_ID, "accountsx:adapter.authlib", AuthlibBridge.class),
                    compute0(AccountsX.MC_ADAPTER_ID, "accountsx:adapter.mc", MinecraftPlatform.class)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute the adapters.", e);
        }
    });

    public static AuthlibBridge<AccountSession> authlibBridge() {
        return INSTANCE.get().authlibBridge();
    }

    public static MinecraftPlatform<AccountSession> getMinecraftPlatform() {
        return INSTANCE.get().minecraftPlatform();
    }

    /**
     * 网络层网关（P1.3）。生产实现为 {@link JdkHttpGateway#INSTANCE}，纯 JDK、不依赖 MC 适配器。
     * 测试可注入假实现以驱动认证流程，无需真实网络。
     *
     * <p><b>⚠️ 安全约束</b>：本方法必须直接返回 {@link JdkHttpGateway#INSTANCE}，
     * 不得改为 {@code INSTANCE.get().httpGateway()} 之类会触发 {@link #INSTANCE}
     * （{@code Suppliers.memoize}）懒加载的实现。否则在非游戏环境（单测 / 非 Fabric 运行时）
     * 中会因 {@code FabricLoader} 不可用而崩溃——而 {@code AccountType} 枚举在类加载时即调用本方法。</p>
     */
    public static HttpGateway getHttpGateway() {
        return JdkHttpGateway.INSTANCE;
    }


    private static <T> T compute0(String modID, String cvName, Class<T> type) {
        try {
            return type.cast(Class.forName(
                    check(
                            check(FabricLoader.getInstance().getModContainer(modID).orElseThrow(
                                    () -> new IllegalStateException("Mod " + modID + " should be bundled in AccountsX!")
                            ).getMetadata().getCustomValue(cvName), CustomValue.CvType.OBJECT, "$").getAsObject().get("class"),
                            CustomValue.CvType.STRING, "$.class"
                    ).getAsString()
            ).getConstructor().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute " + type.getName() + " implementation.", e);
        }
    }

    private static CustomValue check(CustomValue value, CustomValue.CvType type, String path) {
        if (value == null) {
            throw new IllegalStateException(path + "should not be null.");
        }
        if (value.getType() != type) {
            throw new IllegalStateException(path + " should be " + type + " but is " + value.getType() + '.');
        }
        return value;
    }
}
