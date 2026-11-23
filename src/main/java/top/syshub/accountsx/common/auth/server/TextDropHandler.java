package top.syshub.accountsx.common.auth.server;

import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;

import java.io.IOException;

/**
 * 文本拖拽的落点：把 authlib-injector 规范文本变成一次后台加服。
 *
 * <p>事件来源是适配器相关的（26.3+ 的 SDL {@code SDLEventHandlerMixin} 截 DROP_TEXT），但处理本身
 * 与平台无关，故放 common：解析是纯字符串、当场做完；探测是网络 IO，丢进串行工作通道，不占渲染线程。
 * 结果（成功/失败）经 showToast 回到客户端线程弹提示。只认规范 URI，其它文本一律忽略——避免和别的
 * 文本拖拽用途互相干扰。</p>
 */
public final class TextDropHandler {

    private TextDropHandler() {
    }

    public static void handle(String text) {
        String apiRoot = AuthlibInjectorUri.parseApiRoot(text);
        if (apiRoot == null) {
            return;
        }
        TaskScheduler.submit(() -> {
            try {
                AuthServer server = AccountManager.authServers().add(AuthServerKind.YGGDRASIL, apiRoot);
                // 加服走的是后台通道，没有「关闭添加界面」那个刷新时机；开着的账户界面要靠这次通知重建左栏。
                AccountManager.notifyAccountsChanged();
                String name = server.displayName() != null ? server.displayName() : apiRoot;
                Platforms.getMinecraftPlatform().showToast(
                        "accountsx.account.server.drag_title",
                        "accountsx.account.server.drag_added", name);
            } catch (IOException | RuntimeException e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                Platforms.getMinecraftPlatform().showToast(
                        "accountsx.account.fail.title",
                        "accountsx.account.server.fail", reason);
            }
        });
    }
}
