package top.syshub.accountsx.common.accounts.impl.injector;

/**
 * 当前会话的注入器账号，其认证服务器是否能签发**真实**的玩家档案密钥对签名
 * （1.19+ 的 profile key pair，{@code player/certificates} 端点）。
 *
 * <p>背景：多数 authlib-injector 服务器没有 certificates 端点，各 MC 适配器的
 * {@code YggdrasilUserApiServiceMixin} 会拦截 {@code getKeyPair()} 返回一个自签的
 * 假密钥对（空签名），避免切号时 authlib 直接抛异常。但 drasl 4.x 等实现了该端点的
 * 服务器（元数据 {@code meta.feature.enable_profile_key=true}）本可以签发被
 * 「同样挂着 authlib-injector 指向该服务器的服务端」信任的真实签名——此时假密钥对
 * 反而会让服务端以「无效的玩家档案公钥签名」踢人。</p>
 *
 * <p>因此登录链路（{@code AccountManager.loginAccount}）在拿到账号上下文时写入本标志，
 * mixin 仅在标志为 false 时才注入假密钥对。标志是进程级的：同一时刻游戏里只有一个
 * 生效会话，切号即覆盖，volatile 足够。</p>
 */
public final class InjectorProfileKeys {
    private static volatile boolean supported;

    private InjectorProfileKeys() {}

    public static void set(boolean value) {
        supported = value;
    }

    public static boolean isSupported() {
        return supported;
    }
}
