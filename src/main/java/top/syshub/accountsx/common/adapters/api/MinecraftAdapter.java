package top.syshub.accountsx.common.adapters.api;

/**
 * @deprecated since 2.0.0. Use {@link MinecraftPlatform} instead. This interface is retained as an empty shell
 * for one refactoring phase to avoid breaking existing adapter implementations.
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public interface MinecraftAdapter<S extends AccountSession> extends MinecraftPlatform<S> {
}
