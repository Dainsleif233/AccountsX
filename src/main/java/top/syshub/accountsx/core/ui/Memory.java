package top.syshub.accountsx.core.ui;

public interface Memory {
    <T> void set(String guid, T value);

    <T> T get(String guid, Class<T> type);

    boolean isScreenClosed();
}
