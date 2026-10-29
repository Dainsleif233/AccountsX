package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.manager.AccountManager;

/**
 * 登录类界面（外置认证 / 微软 / 离线）的公共骨架。
 *
 * <p>三者的差别只有「填什么、点哪个按钮」，页头、状态行、返回按钮以及「关窗即取消」的语义都一样，
 * 所以收敛到这里。子类的在途请求用 {@link #valid(long)} 判断迟到的结果是否还该采用。</p>
 */
abstract class LoginScreen extends Screen {
    protected static final int PAD = 8;
    protected static final int BTN_H = 20;
    protected static final int INPUT_W = 240;
    protected static final int TOP = 96;

    /** 页头（标题 40 / 副标题 56）之下的第一条可用线：控件上移时不许越过它。 */
    protected static final int HEADER_BOTTOM = 72;

    /**
     * 登录族最高的一屏表单：方式切换行 + 两个输入框 + 登陆按钮（四行、三处间隙）。
     *
     * <p>控件块按最高表单定位，切换 OAuth / 密码时整块才不动——锚点跟着当前模式算，
     * 按钮会在鼠标底下往上跳一格。</p>
     */
    private static final int MAX_FORM_H = 4 * BTN_H + 3 * PAD;

    protected final Screen parent;
    private final Component subtitle;

    /** 轮询线程会读这两个字段，写入全在客户端线程，故声明 volatile。 */
    protected volatile boolean closed;
    protected volatile long requestId;

    protected boolean busy;
    protected Component status;

    protected LoginScreen(Screen parent, String titleKey, Component subtitle) {
        super(Component.translatable(titleKey));
        this.parent = parent;
        this.subtitle = subtitle;
    }

    @Override
    public void onClose() {
        // 关闭即取消：轮询线程与在途回调都以此为准。
        this.closed = true;
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(this.parent);
    }

    @Override
    protected void init() {
        super.init();
        this.updateWidgets();
    }

    /** 控件集合整体重建：子类先 clearWidgets()，再按当前状态摆一遍。 */
    protected abstract void updateWidgets();

    protected final void addField(AbstractWidget widget) {
        this.addRenderableOnly(widget);
        this.addWidget(widget);
    }

    /** 结果是否仍属于当前这次操作：关窗或又发起了新操作都会让它失真。 */
    protected final boolean valid(long rid) {
        return !this.closed && rid == this.requestId;
    }

    protected final int contentX() {
        return this.width / 2 - INPUT_W / 2;
    }

    /** 登录成功（单账号）：入库、让父界面的列表跟上、切换到它，然后回到父界面。 */
    protected final void finish(BaseAccount account) {
        this.finish(java.util.List.of(account), account);
    }

    /**
     * 登录成功（可能一次产出多个账号）：全部入库，只切换到 {@code switchTo}（为空则第一个）。
     *
     * <p>多角色账号里用户勾了几个就建几个，其余留给列表手动切换——每个都自动切一次没有意义，
     * 切账号会重连，回回都断线。切换放在 {@link #onClose()} 之后：界面已经没用了，
     * 而重连过程中留着它会挡住游戏画面。{@code onSwitched} 传空回调是因为
     * {@link AccountManager#selectAccount} 在「已是当前账号」的分支上也会直接调用它。</p>
     */
    protected final void finish(java.util.List<? extends BaseAccount> accounts, BaseAccount switchTo) {
        if (accounts.isEmpty()) {
            return;
        }
        for (BaseAccount account : accounts) {
            AccountManager.addAccount(account);
        }
        Screen parentScreen = this.parent;
        if (parentScreen instanceof AccountScreen accountScreen) {
            accountScreen.syncAccounts();
        }
        BaseAccount target = switchTo != null && accounts.contains(switchTo) ? switchTo : accounts.get(0);
        this.onClose();
        AccountManager.selectAccount(target, () -> {
        });
    }

    /**
     * 失败提示。
     *
     * <p>{@code detail} 只放服务端自己写的说明（见 {@code YggdrasilPasswordFlow#throwIfError}）或异常类型名，
     * 内部类名、HTTP 细节这类东西对用户没有操作价值，只会把「密码错了」淹掉。</p>
     */
    protected static Component failText(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return Component.translatable("accountsx.account.login.fail", detail);
    }

    /** 页头：压暗背景 + 标题 + 副标题（服务器名 / 账号类型）。 */
    protected final void drawHeader(GuiGraphics context) {
        context.fill(0, 0, this.width, this.height, 0x99000000);
        this.center(context, this.title, 40, 0xFFFFFFFF);
        this.center(context, this.subtitle, 56, 0xFFA0A0A0);
    }

    /**
     * 状态行的基线：紧贴「返回」按钮上方预留一行。
     *
     * <p>早期版本把它画在 {@code height - PAD - BTN_H - 16}，窗口不拖到最大时这一行会压住按钮文字；
     * 现在它有自己的保留行，控件也以 {@link #limitY()} 为底边上限，两者互不侵犯。</p>
     */
    protected final int statusY() {
        return this.height - PAD - BTN_H - PAD - this.font.lineHeight;
    }

    /** 控件区的底边上限：页面控件不许越过状态行。 */
    protected final int limitY() {
        return statusY() - PAD;
    }

    /**
     * 控件块顶部：整个登录族共用同一个锚点，只随窗口高度变化。
     *
     * <p>正常从 {@link #TOP} 起；窗口矮到放不下最高表单时整体上移，但不越过页头。
     * 各屏若按自己当前的表单算，微软 / OAuth / 密码三屏的按钮会落在三个不同高度。</p>
     */
    protected final int contentTop() {
        return Math.max(HEADER_BOTTOM, Math.min(TOP, this.limitY() - MAX_FORM_H));
    }

    /**
     * 说明行（登录码、OAuth 提示）的基线：紧贴控件块下沿空一格。
     *
     * <p>微软与外置两屏共用它，否则同一条「登录码」会一屏在按钮上方、一屏在下方。</p>
     */
    protected static int hintBelow(int blockBottom) {
        return blockBottom + PAD;
    }

    /**
     * 页头上方那一行（明文 HTTP 警告、选择提示）的基线：贴着控件块，窗口变矮时一起上移。
     *
     * <p>下界压回页头下沿，否则高 GUI 缩放下（窗口只有两百多像素）这一行会压到副标题上。</p>
     */
    protected final int noticeY() {
        return Math.max(HEADER_BOTTOM, this.contentTop() - 12);
    }

    /**
     * 状态行：失败原因、「正在操作」等。过长时按窗口宽度折行并向上叠放。
     *
     * <p>文案里的服务端说明是一整句任意长度的文本，居中画一行会顶到屏幕两侧；向上叠放而不是向下，
     * 是因为下方紧接着就是「返回」按钮。</p>
     */
    protected final void drawStatus(GuiGraphics context) {
        Component text = this.status;
        if (text == null) {
            return;
        }
        java.util.List<String> lines = this.wrapLines(text.getString(), Math.max(INPUT_W, this.width - PAD * 8));
        int y = this.statusY() - (lines.size() - 1) * this.font.lineHeight;
        for (String line : lines) {
            this.center(context, Component.literal(line), y, 0xFFA0A0A0);
            y += this.font.lineHeight;
        }
    }

    /** 按像素宽度逐字折行（中英混排没有空格可依赖，只能量宽度）。 */
    private java.util.List<String> wrapLines(String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (line.length() > 0 && this.font.width(line.toString() + c) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(c);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    protected final void center(GuiGraphics context, Component text, int y, int color) {
        context.drawCenteredString(this.font, text, this.width / 2, y, color);
    }

    /** 底部「返回」：忙碌时它就是取消入口（onClose 会终止在途请求）。 */
    protected final void addBackButton() {
        this.addField(new ButtonWidget(this.contentX(), this.height - PAD - BTN_H, INPUT_W, BTN_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()));
    }

    /**
     * 密码框遮蔽。
     *
     * <p>各版本 EditBox 的格式化 API 不同，而这份代码要同时编译十几个版本，所以不碰格式化 API：
     * 控件里存真值，画完控件后把输入框内部盖掉，再按长度画星号。只用 fill + 文本绘制，两族都有。</p>
     */
    protected final void drawPasswordMask(GuiGraphics context, EditBox box, int x, int y) {
        if (box == null || box.getValue().isEmpty()) {
            return;
        }
        context.fill(x + 1, y + 1, x + INPUT_W - 1, y + BTN_H - 1, 0xFF000000);
        String masked = "\u002a".repeat(box.getValue().length());
        context.drawString(this.font, masked, x + 4, y + 6, 0xFFE0E0E0);
    }

}
