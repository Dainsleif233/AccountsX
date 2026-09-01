package top.syshub.accountsx.common.adapters.api;

/**
 * @deprecated since 2.0.0. Use {@link AuthlibBridge} instead. This interface is retained as an empty shell
 * for one refactoring phase to avoid breaking existing adapter implementations.
 */
@Deprecated(since = "2.0.0", forRemoval = true)
public interface AuthlibAdapter<S extends AccountSession> extends AuthlibBridge<S> {
}
