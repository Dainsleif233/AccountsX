package top.syshub.accountsx.core.adapters.api;

/**
 * @deprecated Use {@link AuthlibBridge} instead. This interface is retained as an empty shell
 * for one refactoring phase to avoid breaking existing adapter implementations.
 */
@Deprecated(forRemoval = true)
public interface AuthlibAdapter<S extends AccountSession> extends AuthlibBridge<S> {
}
