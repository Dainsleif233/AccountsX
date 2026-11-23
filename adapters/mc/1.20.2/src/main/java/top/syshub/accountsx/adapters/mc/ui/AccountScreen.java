package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.server.AuthServerKind;
import top.syshub.accountsx.common.manager.AccountManager;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.SelectionStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 账号列表界面。
 *
 * <p>左侧是单选的账户筛选（全部 / 微软 / 各外置认证服务器 / 离线），右侧只显示被筛选出的账号；
 * 「添加认证服务器」固定在左下角，「添加账户」固定在右侧列表底部（「全部账户」视图下不显示，
 * 因为此时没有确定的账号类型可加）。</p>
 */
public class AccountScreen extends Screen {
    private static final int PAD = 8;
    private static final int SIDEBAR_W = 150;
    private static final int BTN_H = 20;
    private static final int BTN_GAP = 4;
    private static final int ENTRY_H = 36;
    private static final int LIST_TOP = 48;
    /** 左栏筛选列表的顶部：返回按钮下面空 16px。 */
    private static final int FILTER_TOP = PAD + 12 + BTN_H + 16;
    private static final int SCROLLBAR_W = 3;
    /** 选中项不在可见范围内（被滚出去）时 selectedFrameY 的哨兵值。 */
    private static final int NO_FRAME = Integer.MIN_VALUE;

    private final Screen parent;
    private AccountListWidget accountListWidget;

    /** 别处改了账号集合（多实例）时重同步界面；同一实例只订阅一次，关界面时退订。 */
    private final Runnable changeListener = this::onAccountsChanged;

    private final List<Filter> filters = new ArrayList<>();
    private int selectedFilter;

    /** 左栏滚到的第一项下标：认证服务器多了以后靠滚轮翻，免得压住左下角两个按钮。 */
    private int filterScroll;
    /** 上次布局算出的可见行数；滚动上限与滚动条长度都按它来。 */
    private int visibleRows = 1;
    /** 本次布局里选中项的行顶 y，渲染高亮用（随滚动变化）。 */
    private int selectedFrameY = NO_FRAME;
    /** 筛选项按钮的右边缘：出现滚动条时按钮会收窄，高亮外圈跟着它走。 */
    private int filterRowRight = SIDEBAR_W - PAD;

    public AccountScreen(Screen parent) {
        super(Component.translatable("accountsx.account.general.account_list"));
        this.parent = parent;
    }

    public void onClose() {
        AccountManager.removeChangeListener(this.changeListener);
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(this.parent);
    }

    public void syncAccounts() {
        if (this.accountListWidget != null) {
            this.accountListWidget.syncAccounts();
        }
    }

    /**
     * 别的实例改动账号/认证服务器后刷新界面（客户端线程）。
     *
     * <p>整块重建而不只同步列表：对端可能加了认证服务器，左栏筛选项也得跟着变，
     * 否则新账号在列表里却在左栏找不到对应分类。</p>
     */
    private void onAccountsChanged() {
        if (this.minecraft == null) {
            return;
        }
        this.rebuildFilters();
        this.updateWidgets();
    }

    @Override
    protected void init() {
        super.init();
        // init 会在窗口尺寸变化时重复调用：addChangeListener 对同一实例幂等
        AccountManager.addChangeListener(this.changeListener);
        this.rebuildFilters();
        this.updateWidgets();
    }

    private void rebuildFilters() {
        // 记住当前选中的身份（服务器按 apiRoot，其余按类型），重建后按身份恢复下标：
        // 对端把服务器重排后，旧下标会指向另一台服务器，↑/↓/移除的操作对象就错了。
        Filter previous = this.selectedFilter >= 0 && this.selectedFilter < this.filters.size()
                ? this.filters.get(this.selectedFilter) : null;
        String previousApiRoot = previous != null && previous.server() != null ? previous.server().apiRoot() : null;
        AccountType previousType = previous != null ? previous.addType() : null;
        this.filters.clear();
        this.filters.add(new Filter(Component.translatable("accountsx.account.filter.all"),
                account -> true, null, null));
        this.filters.add(new Filter(Component.translatable("accountsx.account.filter.microsoft"),
                account -> account.getAccountType() == AccountType.MICROSOFT, AccountType.MICROSOFT, null));
        for (AuthServer server : AccountManager.authServers().servers()) {
            // 统一通行证暂不进入筛选（该类型后续会移除）；只有外置认证服务器有筛选价值。
            if (server.kind() != AuthServerKind.YGGDRASIL) {
                continue;
            }
            String apiRoot = server.apiRoot();
            if (apiRoot == null) {
                continue;
            }
            String displayName = server.displayName();
            Component label = Component.literal(displayName == null || displayName.isBlank() ? apiRoot : displayName);
            this.filters.add(new Filter(label, account -> isOnServer(account, apiRoot), AccountType.AUTHLIB_INJECTOR, server));
        }
        this.filters.add(new Filter(Component.translatable("accountsx.account.filter.offline"),
                account -> account.getAccountType() == AccountType.OFFLINE, AccountType.OFFLINE, null));
        if (previous != null) {
            int restored = -1;
            for (int i = 0; i < this.filters.size(); i++) {
                Filter f = this.filters.get(i);
                if (previousApiRoot != null) {
                    if (f.server() != null && previousApiRoot.equals(f.server().apiRoot())) {
                        restored = i;
                        break;
                    }
                } else if (f.server() == null && f.addType() == previousType) {
                    restored = i;
                    break;
                }
            }
            // 选中的服务器被对端删了：回落「全部」，不要停在一个指向别人的下标上。
            this.selectedFilter = restored >= 0 ? restored : 0;
        } else if (this.selectedFilter >= this.filters.size()) {
            this.selectedFilter = 0;
        }
    }

    private void updateWidgets() {
        this.clearWidgets();

        int x = PAD;
        int buttonWidth = SIDEBAR_W - PAD * 2;

        this.addField(new ButtonWidget(x, PAD + 12, buttonWidth, BTN_H,
                Component.translatable("accountsx.general.action.close"),
                button -> this.onClose()));

        int bottomY = this.height - PAD - BTN_H;
        AuthServer selectedServer = this.filters.get(this.selectedFilter).server();

        // 筛选列表夹在返回按钮和左下角按钮之间：选中服务器时多一行「上移/下移」+「移除」，
        // 放不下就只建可见的那几行，剩下的靠滚动翻。
        int filterBottom = bottomY - BTN_GAP - (selectedServer != null ? 2 * (BTN_H + BTN_GAP) : 0);
        int rowStep = BTN_H + BTN_GAP;
        this.visibleRows = Math.max(1, (filterBottom - FILTER_TOP + BTN_GAP) / rowStep);
        this.filterScroll = Math.max(0, Math.min(this.filterScroll, this.maxFilterScroll()));
        this.selectedFrameY = NO_FRAME;

        int rowWidth = this.maxFilterScroll() > 0 ? buttonWidth - SCROLLBAR_W - BTN_GAP : buttonWidth;
        this.filterRowRight = PAD + rowWidth;
        for (int row = 0; row < this.visibleRows && this.filterScroll + row < this.filters.size(); row++) {
            int index = this.filterScroll + row;
            int rowY = FILTER_TOP + row * rowStep;
            this.addField(new ButtonWidget(x, rowY, rowWidth, BTN_H, this.filters.get(index).label(),
                    button -> this.selectFilter(index)));
            if (index == this.selectedFilter) {
                this.selectedFrameY = rowY;
            }
        }
        if (selectedServer != null) {
            // 服务器排序：上移/下移各占半宽，与账号列表的 ↑/↓ 同一交互。
            int reorderY = bottomY - 2 * BTN_H - 2 * BTN_GAP;
            int halfW = (buttonWidth - BTN_GAP) / 2;
            this.addField(new ButtonWidget(x, reorderY, halfW, BTN_H,
                    Component.literal("↑"), button -> this.moveServer(selectedServer, -1)));
            this.addField(new ButtonWidget(x + halfW + BTN_GAP, reorderY, buttonWidth - halfW - BTN_GAP, BTN_H,
                    Component.literal("↓"), button -> this.moveServer(selectedServer, 1)));
            this.addField(new ButtonWidget(x, bottomY - BTN_H - BTN_GAP, buttonWidth, BTN_H,
                    Component.translatable("accountsx.account.general.remove_auth_server"),
                    button -> this.removeServer(selectedServer)));
        }
        this.addField(new ButtonWidget(x, bottomY, buttonWidth, BTN_H,
                Component.translatable("accountsx.account.general.add_auth_server"),
                button -> this.openAddServer()));

        int listLeft = SIDEBAR_W + PAD;
        int listRight = this.width - PAD;
        int listBottom = this.height - PAD - (this.selectedFilter == 0 ? 0 : BTN_H + BTN_GAP);

        if (this.accountListWidget == null) {
            this.accountListWidget = new AccountListWidget(this.minecraft, listLeft, listRight, LIST_TOP, listBottom, ENTRY_H);
        } else {
            this.accountListWidget.updateSize(listLeft, listRight, LIST_TOP, listBottom);
        }
        this.accountListWidget.setFilter(this.filters.get(this.selectedFilter).predicate());
        this.accountListWidget.setReorderable(this.selectedFilter == 0);
        this.addWidget(this.accountListWidget);

        if (this.selectedFilter != 0) {
            this.addField(new ButtonWidget(listLeft, listBottom + BTN_GAP, listRight - listLeft, BTN_H,
                    Component.translatable("accountsx.account.general.add_account"),
                    button -> this.openAddAccount(this.filters.get(this.selectedFilter))));
        }
    }

    private void selectFilter(int index) {
        if (index == this.selectedFilter) {
            return;
        }

        this.selectedFilter = index;
        this.updateWidgets();
    }

    /** 移动选中服务器后重建左栏，并按 apiRoot 找回它（排序后下标会变），保持选中不丢。 */
    private void moveServer(AuthServer server, int delta) {
        String apiRoot = server.apiRoot();
        AccountManager.moveServer(server, delta);
        this.rebuildFilters();
        for (int i = 0; i < this.filters.size(); i++) {
            AuthServer s = this.filters.get(i).server();
            if (s != null && apiRoot.equals(s.apiRoot())) {
                this.selectedFilter = i;
                break;
            }
        }
        this.updateWidgets();
    }

    /** 左栏最多能滚多少项；0 表示全部放得下。 */
    private int maxFilterScroll() {
        return Math.max(0, this.filters.size() - this.visibleRows);
    }

    /**
     * 鼠标在左栏时滚轮翻筛选列表，一格一项。
     *
     * @return 是否真的滚动了；已经到头就让回去，右侧账号列表还要用它
     */
    private boolean scrollFilters(double mouseX, double amount) {
        if (mouseX >= SIDEBAR_W || amount == 0 || this.maxFilterScroll() == 0) {
            return false;
        }

        int next = this.filterScroll - (int) Math.signum(amount);
        next = Math.max(0, Math.min(next, this.maxFilterScroll()));
        if (next == this.filterScroll) {
            return false;
        }

        this.filterScroll = next;
        this.updateWidgets();
        return true;
    }

    /** 与「删服务器连带删账号」共用仓储里的同一条判定，避免同一条规则在两处各写一遍。 */
    private static boolean isOnServer(BaseAccount account, String apiRoot) {
        return AccountManager.isOnServer(account, apiRoot);
    }

    // 1.20.2 起 Screen.mouseScrolled 多了横向分量。本文件与 adapters/mc/1.20 的差异只有下面这个签名，
    // 改这一屏的布局时两处都要跟上。
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.scrollFilters(mouseX, verticalAmount)) {
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        // 版本间 renderBackground 的参数不同，这里统一自绘一层压暗，避免依赖具体签名。
        context.fill(0, 0, this.width, this.height, 0x99000000);
        context.fill(0, 0, SIDEBAR_W, this.height, 0x66000000);
        context.fill(SIDEBAR_W, 0, SIDEBAR_W + 2, this.height, 0x40FFFFFF);

        // 选中筛选项：外圈垫在控件下面露出来；与登录界面的模式切换共用 SelectionStyle。
        if (this.selectedFrameY != NO_FRAME) {
            context.fill(PAD - SelectionStyle.INSET_X, this.selectedFrameY - SelectionStyle.INSET_Y,
                    this.filterRowRight + SelectionStyle.INSET_X, this.selectedFrameY + BTN_H + SelectionStyle.INSET_Y,
                    SelectionStyle.FRAME);
        }

        this.accountListWidget.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawFilterScrollbar(context);

        int centerX = (SIDEBAR_W + PAD + this.width - PAD) / 2;
        BaseAccount current = AccountManager.getCurrentAccount();
        Component header = current == null
                ? Component.translatable("accountsx.account.general.current_account.none")
                : Component.translatable("accountsx.account.general.current_account", current.getAccountStorage().getPlayerName());
        context.drawCenteredString(this.font, header, centerX, PAD, 0xFFFFFFFF);
        if (current != null) {
            // 外置账号的类型名对所有服务器都一样，没有信息量；来源是它所在的认证服务器。
            String serverName = AccountManager.serverDisplayName(current);
            Component subtitle = TaskScheduler.isRunning()
                    ? Component.translatable("accountsx.account.general.operating")
                    : serverName != null ? Component.literal(serverName) : I18N.TRANSLATOR.translate(current.getAccountType());
            context.drawCenteredString(this.font, subtitle, centerX, PAD + 12, 0xFFA0A0A0);
        }
    }

    public void addField(AbstractWidget drawable) {
        this.addRenderableOnly(drawable);
        this.addWidget(drawable);
    }

    /** 打开「添加认证服务器」表单。 */
    private void openAddServer() {
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }
        minecraft.setScreen(new ServerScreen(this));
    }

    /** 「添加账户」：外置服务器走 OAuth/密码登录界面，其余类型仍走通用表单登录。 */
    private void openAddAccount(Filter filter) {
        Minecraft minecraft = this.minecraft;
        if (minecraft == null) {
            return;
        }
        if (filter.server() != null) {
            minecraft.setScreen(new ExternalLoginScreen(this, filter.server()));
            return;
        }
        AccountType addType = filter.addType();
        if (addType == AccountType.MICROSOFT) {
            minecraft.setScreen(new MicrosoftLoginScreen(this));
        } else if (addType == AccountType.OFFLINE) {
            minecraft.setScreen(new OfflineLoginScreen(this));
        }
    }

    /**
     * 移除选中的认证服务器，并连带删除它的账号。
     *
     * <p>服务器条目是这些账号的登录端点、公钥与 skinDomains 来源：条目删掉后它们既登录不了也刷新不了，
     * 留着只会在列表里占位。当前账号在其中时由 Manager 回落到默认账号。删完回到「全部账户」，
     * 避免选中项指向已经消失的筛选。</p>
     */
    private void removeServer(AuthServer server) {
        AccountManager.removeServer(server);
        this.selectedFilter = 0;
        this.filterScroll = 0;
        this.rebuildFilters();
        this.updateWidgets();
    }

    /** 筛选放不下时在左栏右侧画一条滚动条；位置跟随 {@link #filterScroll}。 */
    private void drawFilterScrollbar(GuiGraphics context) {
        int maxScroll = this.maxFilterScroll();
        if (maxScroll == 0) {
            return;
        }

        int trackTop = FILTER_TOP;
        int trackHeight = this.visibleRows * (BTN_H + BTN_GAP) - BTN_GAP;
        int scrollbarX = SIDEBAR_W - PAD - SCROLLBAR_W;
        context.fill(scrollbarX, trackTop, scrollbarX + SCROLLBAR_W, trackTop + trackHeight, 0x33FFFFFF);

        int thumbHeight = Math.max(12, trackHeight * this.visibleRows / this.filters.size());
        int thumbTop = trackTop + (trackHeight - thumbHeight) * this.filterScroll / maxScroll;
        context.fill(scrollbarX, thumbTop, scrollbarX + SCROLLBAR_W, thumbTop + thumbHeight, SelectionStyle.FRAME);
    }

    /**
     * 左栏一项筛选：{@code predicate} 决定右栏显示哪些账号，{@code addType} / {@code server} 决定
     * 「添加账户」走哪条登录路径（统一通行证与「全部账户」两项都没有可加的具体类型）。
     */
    private record Filter(Component label, Predicate<BaseAccount> predicate, AccountType addType, AuthServer server) {
    }
}
