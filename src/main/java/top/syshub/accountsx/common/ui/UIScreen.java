package top.syshub.accountsx.common.ui;

/**
 * ScreenUI is an adapter between Minecraft's changing screen API and static codes.
 * Every time when Minecraft updates its screen API, we only need to update the implementation of ScreenUI.
 */
public interface UIScreen {
    void setTitle(String description);

    void putTextInput(String guid, String description);

    String getTextInput(String guid);
}
