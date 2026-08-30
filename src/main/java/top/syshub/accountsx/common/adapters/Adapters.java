package top.syshub.accountsx.common.adapters;

import top.syshub.accountsx.common.net.HttpGateway;

/**
 * @deprecated Use {@link Platforms} instead. This class is retained only for
 * {@link #getHttpGateway()} which is used by tests. The adapter-forwarding methods
 * have been removed as no core code references them after P1.5; obtain the
 * platform/authlib bridges via {@link Platforms#getMinecraftPlatform()} and
 * {@link Platforms#authlibBridge()} instead.
 */
@Deprecated(forRemoval = true)
public final class Adapters {
    private Adapters() {
    }

    /**
     * @deprecated Use {@link Platforms#getHttpGateway()} instead.
     */
    @Deprecated(forRemoval = true)
    public static HttpGateway getHttpGateway() {
        return Platforms.getHttpGateway();
    }
}
