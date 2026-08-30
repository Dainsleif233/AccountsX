package top.syshub.accountsx.common.ui;

public interface Memory {
    <T> void set(String guid, T value);

    <T> T get(String guid, Class<T> type);

    boolean isScreenClosed();
}
