package top.syshub.accountsx.core.adapters.api;

/**
 * @deprecated Use {@link MinecraftPlatform} instead. This interface is retained as an empty shell
 * for one refactoring phase to avoid breaking existing adapter implementations.
 */
@Deprecated(forRemoval = true)
public interface MinecraftAdapter<S extends AccountSession> extends MinecraftPlatform<S> {
}
