package top.syshub.accountsx.adapters.mc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import top.syshub.accountsx.common.accounts.BaseAccount;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccountProvider;
import top.syshub.accountsx.common.accounts.model.AccountType;
import top.syshub.accountsx.common.adapters.Platforms;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.server.ServerAddressSecurity;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilDeviceCodeFlow;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilDeviceCodeFlow.DeviceCode;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilPasswordFlow;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilPasswordFlow.LoginDraft;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilProfiles.Profile;
import top.syshub.accountsx.common.task.TaskScheduler;
import top.syshub.accountsx.common.ui.SelectionStyle;
import top.syshub.accountsx.common.utils.image.AvatarCache;
import top.syshub.accountsx.common.utils.image.AvatarPixels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 外置认证登录界面（OAuth / 密码两种方式）。
 *
 * <p>服务器由左栏筛选确定，这里不再输入地址、也不发探测请求：端点全部取自注册表缓存。OAuth 分两步
 * ——先取设备码展示并复制，再打开浏览器并轮询 token。关闭窗口即取消：置 {@code closed} 后轮询与
 * 回调都会尽快放弃，迟到的结果不会写进账号列表。</p>
 *
 * <p>密码登录分两段：先只认证（{@code /authserver/authenticate}）拿到角色清单，再由用户决定绑哪个。
 * 一个 Yggdrasil 账号可能同时有多个角色，若不选就绑，用户会莫名其妙登成别的角色；绑定的动作是带
 * {@code selectedProfile} 的 refresh，所以勾选几个角色就建几个账号。</p>
 */
public class ExternalLoginScreen extends LoginScreen {
    private static final int TOGGLE_W = (INPUT_W - PAD) / 2;

    /** 选择态头像：画在角色按钮左侧，边长与内缩都按 16 像素的整数倍排。 */
    private static final int AVATAR_SIZE = 16;
    private static final int AVATAR_INSET = 2;

    private final AuthServer server;
    private final AbstractInjectorAccountProvider<?> provider;
    private final boolean oauthAvailable;

    private boolean oauthMode;
    private DeviceCode deviceCode;

    private EditBox usernameBox;
    private EditBox passwordBox;
    private int passwordX;
    private int passwordY;

    /** 多角色选择态：{@code candidates} 非 null 表示已认证完、正等用户勾选。 */
    private LoginDraft draft;
    private List<Profile> candidates;
    private final Set<String> chosen = new LinkedHashSet<>();
    private int candidatePage;

    /**
     * 选择态下重放认证要用用户名/密码：绑定第二个角色要再认证一次，不该让用户重新输入。
     * 只在选择态存活，关窗即清空，不写任何日志。
     */
    private String pendingUsername;
    private String pendingSecret;

    /** 候选角色 uuid → 皮肤缓存键；取不到的角色缺席，界面回落到占位。 */
    private Map<String, String> candidateAvatars = Map.of();

    /** 已注册的头像纹理，按皮肤缓存键复用——重绘时不能再读盘、更不能重复注册。 */
    /** 已解码的头像像素（皮肤缓存键 → 16×16 ARGB），翻页重绘不重复解码。 */
    private final Map<String, int[]> avatarTextures = new HashMap<>();

    public ExternalLoginScreen(Screen parent, AuthServer server) {
        super(parent, "accountsx.account.general.login", label(server));
        this.server = server;
        this.provider = (AbstractInjectorAccountProvider<?>) AccountType.AUTHLIB_INJECTOR.getAccountProvider();
        this.oauthAvailable = YggdrasilDeviceCodeFlow.supportsDeviceCode(server);
        this.oauthMode = this.oauthAvailable;
    }

    private static Component label(AuthServer server) {
        String name = server.displayName();
        return Component.literal(name == null || name.isBlank() ? server.apiRoot() : name);
    }

    @Override
    protected void updateWidgets() {
        this.usernameBox = null;
        this.passwordBox = null;
        this.clearWidgets();

        int x = this.contentX();

        if (this.candidates != null) {
            this.addProfileSelection(x);
            return;
        }

        // 锚点由基类整族统一给出：切换 OAuth / 密码时这一行不会动。
        int y = this.contentTop();

        if (this.oauthAvailable) {
            this.addField(new ButtonWidget(x, y, TOGGLE_W, BTN_H,
                    Component.translatable("accountsx.account.external.mode.oauth"),
                    button -> this.selectMode(true)));
            this.addField(new ButtonWidget(x + TOGGLE_W + PAD, y, INPUT_W - TOGGLE_W - PAD, BTN_H,
                    Component.translatable("accountsx.account.external.mode.password"),
                    button -> this.selectMode(false)));
            y += BTN_H + PAD;
        }

        if (this.oauthMode) {
            DeviceCode code = this.deviceCode;
            if (code == null) {
                ButtonWidget fetch = new ButtonWidget(x, y, INPUT_W, BTN_H,
                        Component.translatable("accountsx.account.external.fetch_code"),
                        button -> this.fetchCode());
                fetch.active = !this.busy;
                this.addField(fetch);
            } else {
                // 拿到码之后给两个动作：直接去浏览器，或只把链接交给用户自己打开。
                ButtonWidget open = new ButtonWidget(x, y, TOGGLE_W, BTN_H,
                        Component.translatable("accountsx.account.external.open_browser"),
                        button -> this.loginOAuth(code));
                open.active = !this.busy;
                this.addField(open);
                ButtonWidget copy = new ButtonWidget(x + TOGGLE_W + PAD, y, INPUT_W - TOGGLE_W - PAD, BTN_H,
                        Component.translatable("accountsx.account.external.copy_link"),
                        button -> this.copyLink(code));
                copy.active = !this.busy;
                this.addField(copy);
            }
            y += BTN_H + PAD;
        } else {
            this.usernameBox = new EditBox(this.font, x, y, INPUT_W, BTN_H,
                    Component.translatable("accountsx.account.objects.user_name"));
            // 不少 Yggdrasil 服务端允许用邮箱登录，提示写成两可，免得用户以为只能填 ID。
            this.usernameBox.setHint(Component.translatable("accountsx.account.external.username.hint"));
            this.usernameBox.setMaxLength(128);
            this.addField(this.usernameBox);
            y += BTN_H + PAD;

            this.passwordX = x;
            this.passwordY = y;
            this.passwordBox = new EditBox(this.font, x, y, INPUT_W, BTN_H,
                    Component.translatable("accountsx.account.objects.user_password"));
            this.passwordBox.setHint(Component.translatable("accountsx.account.objects.user_password"));
            this.passwordBox.setMaxLength(128);
            this.addField(this.passwordBox);
            y += BTN_H + PAD;

            ButtonWidget login = new ButtonWidget(x, y, INPUT_W, BTN_H,
                    Component.translatable("accountsx.account.general.login"),
                    button -> this.loginPassword());
            login.active = !this.busy;
            this.addField(login);
            y += BTN_H + PAD;
        }

        this.addBackButton();
    }

    /**
     * 选择态控件：一页角色按钮 + 「添加所选角色」，角色多时左右各一个翻页按钮。
     *
     * <p>角色按钮不带 selected 样式，用前缀符号表达勾选，这样点击就是切换、不需要额外的复选框控件。</p>
     */
    private void addProfileSelection(int x) {
        List<Profile> list = this.candidates;
        if (list == null) {
            return;
        }

        int capacity = this.rowCapacity();
        int pages = this.pageCount();
        // 窗口变矮可能让当前页越界，这里夹一次而不是在 flipPage 之外依赖不变量。
        this.candidatePage = Math.min(this.candidatePage, pages - 1);
        int start = this.candidatePage * capacity;
        int end = Math.min(list.size(), start + capacity);
        int y = this.selectionRowsTop(capacity);
        for (int i = start; i < end; i++) {
            Profile profile = list.get(i);
            boolean selected = this.chosen.contains(profile.playerUuid());
            ButtonWidget row = new ButtonWidget(x, y, INPUT_W, BTN_H,
                    Component.literal((selected ? "\u2714 " : "   ") + profile.playerName()),
                    button -> this.toggle(profile));
            row.active = !this.busy;
            this.addField(row);
            y += BTN_H + PAD;
        }

        int confirmY = this.confirmY();
        if (pages > 1) {
            ButtonWidget previous = new ButtonWidget(x, confirmY, BTN_H, BTN_H,
                    Component.literal("\u25c0"), button -> this.flipPage(-1));
            previous.active = !this.busy;
            this.addField(previous);
            ButtonWidget next = new ButtonWidget(x + INPUT_W - BTN_H, confirmY, BTN_H, BTN_H,
                    Component.literal("\u25b6"), button -> this.flipPage(1));
            next.active = !this.busy;
            this.addField(next);
        }

        int margin = pages > 1 ? BTN_H + PAD : 0;
        ButtonWidget confirm = new ButtonWidget(x + margin, confirmY, INPUT_W - margin * 2, BTN_H,
                Component.translatable("accountsx.account.external.select_profile.confirm"),
                button -> this.confirmSelection());
        confirm.active = !this.busy;
        this.addField(confirm);

        this.addBackButton();
    }

    /** 「添加所选角色」那一行：紧贴状态行上方（状态行本身在返回按钮上方）。 */
    private int confirmY() {
        return this.limitY() - BTN_H;
    }

    /**
     * 一页能放几行角色。
     *
     * <p>正常高度下按 TOP 起算；窗口太矮时退到页头下方，能塞几行塞几行——宁可挤也不能把控件排出屏幕。</p>
     */
    private int rowCapacity() {
        int available = this.confirmY() - PAD - this.contentTop();
        return Math.max(1, available / (BTN_H + PAD));
    }

    /** 角色行起点：与登录表单同一个锚点，行数多到压住确认按钮时整体上移。 */
    private int selectionRowsTop(int capacity) {
        return Math.min(this.contentTop(), this.confirmY() - PAD - capacity * (BTN_H + PAD));
    }

    private int pageCount() {
        List<Profile> list = this.candidates;
        if (list == null) {
            return 1;
        }
        int capacity = this.rowCapacity();
        return Math.max(1, (list.size() + capacity - 1) / capacity);
    }

    private void toggle(Profile profile) {
        if (this.busy) {
            return;
        }
        String uuid = profile.playerUuid();
        if (!this.chosen.remove(uuid)) {
            this.chosen.add(uuid);
        }
        this.status = null;
        this.updateWidgets();
    }

    private void flipPage(int delta) {
        if (this.busy) {
            return;
        }
        int pages = this.pageCount();
        if (pages <= 1) {
            return;
        }
        this.candidatePage = Math.floorMod(this.candidatePage + delta, pages);
        this.updateWidgets();
    }

    private void confirmSelection() {
        LoginDraft current = this.draft;
        List<Profile> list = this.candidates;
        if (this.busy || current == null || list == null) {
            return;
        }
        List<Profile> selected = new ArrayList<>();
        for (Profile profile : list) {
            if (this.chosen.contains(profile.playerUuid())) {
                selected.add(profile);
            }
        }
        if (selected.isEmpty()) {
            this.status = Component.translatable("accountsx.account.external.select_profile.none");
            this.updateWidgets();
            return;
        }
        this.addSelected(this.pendingUsername, this.pendingSecret, current, selected);
    }

    private void clearSelection() {
        this.draft = null;
        this.candidates = null;
        this.chosen.clear();
        this.candidatePage = 0;
        this.pendingUsername = null;
        this.pendingSecret = null;
        this.candidateAvatars = Map.of();
        this.avatarTextures.clear();
    }

    @Override
    public void onClose() {
        // 关窗即丢弃暂存的凭据：选择态可能停留很久，没人保证用户会回来点确认。
        this.clearSelection();
        super.onClose();
    }

    private void selectMode(boolean oauth) {
        if (this.busy || this.oauthMode == oauth) {
            return;
        }
        this.oauthMode = oauth;
        this.deviceCode = null;
        this.status = null;
        this.updateWidgets();
    }

    private void fetchCode() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.status = Component.translatable("accountsx.account.external.code.generating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            DeviceCode code = null;
            Component failure = null;
            try {
                code = this.provider.fetchDeviceCode(this.server);
            } catch (Exception e) {
                failure = failText(e);
            }

            DeviceCode result = code;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (result == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.deviceCode = result;
                this.status = null;
                // 验证码是给人看的短码，复制进剪贴板省一次手抄。
                Platforms.getMinecraftPlatform().copyText(result.userCode());
                this.updateWidgets();
            });
        });
    }

    private void loginOAuth(DeviceCode code) {
        if (this.busy) {
            return;
        }
        String url = code.browserUrl();
        if (url != null) {
            Platforms.getMinecraftPlatform().openBrowser(url);
        }

        this.busy = true;
        this.status = Component.translatable("accountsx.account.general.operating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            BaseAccount account = null;
            Component failure = null;
            try {
                account = this.provider.loginDeviceCode(this.server, code,
                        () -> this.closed || rid != this.requestId);
            } catch (Exception e) {
                failure = failText(e);
            }

            BaseAccount created = account;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (created == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.finish(created);
            });
        });
    }

    private void loginPassword() {
        EditBox user = this.usernameBox;
        EditBox password = this.passwordBox;
        if (this.busy || user == null || password == null) {
            return;
        }
        String username = user.getValue().trim();
        if (username.isEmpty()) {
            this.status = Component.translatable("accountsx.account.external.error.username");
            return;
        }

        String secret = password.getValue();
        this.busy = true;
        this.status = Component.translatable("accountsx.account.general.operating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            LoginDraft result = null;
            Map<String, String> avatars = Map.of();
            Component failure = null;
            try {
                result = this.provider.loginPasswordDraft(this.server, username, secret);
                // 头像跟着认证一起取：选择态只画界面不联网，每个角色一次请求放在这一趟 worker 里。
                if (result.profiles().size() > 1) {
                    avatars = this.provider.avatarKeys(this.server, result.profiles());
                }
            } catch (Exception e) {
                failure = failText(e);
            }

            LoginDraft authenticated = result;
            Map<String, String> avatarKeys = avatars;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (authenticated == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                this.afterAuthenticate(username, secret, authenticated, avatarKeys);
            });
        });
    }

    /** 认证成功后分流：只有一个候选就直接建号，多个则进选择态（服务端已绑的那个预先勾上）。 */
    private void afterAuthenticate(String username, String secret, LoginDraft authenticated,
            Map<String, String> avatars) {
        Profile bound = authenticated.bound();
        List<Profile> available = authenticated.profiles();
        if (available.size() == 1) {
            this.addSelected(username, secret, authenticated, available);
            return;
        }

        this.draft = authenticated;
        this.candidates = available;
        this.chosen.clear();
        if (bound != null) {
            // 服务端已经绑了一个：默认就加它，其余角色要用户显式勾。
            this.chosen.add(bound.playerUuid());
        } else {
            for (Profile profile : available) {
                this.chosen.add(profile.playerUuid());
            }
        }
        this.pendingUsername = username;
        this.pendingSecret = secret;
        this.candidateAvatars = avatars;
        this.candidatePage = 0;
        this.status = null;
        this.updateWidgets();
    }

    /** 为每个勾选的角色建一个账号；失败时留着选择态，用户可改选重试（密码不必重输）。 */
    private void addSelected(String username, String secret, LoginDraft authenticated, List<Profile> selected) {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.status = Component.translatable("accountsx.account.general.operating");
        long rid = ++this.requestId;
        this.updateWidgets();

        TaskScheduler.submit(() -> {
            List<? extends BaseAccount> created = null;
            Component failure = null;
            try {
                created = this.provider.loginPasswordSelected(this.server, username, secret, authenticated, selected);
            } catch (Exception e) {
                failure = failText(e);
            }

            List<? extends BaseAccount> accounts = created;
            Component error = failure;
            Minecraft minecraft = this.minecraft;
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (!this.valid(rid)) {
                    return;
                }
                this.busy = false;
                if (accounts == null) {
                    this.status = error;
                    this.updateWidgets();
                    return;
                }
                BaseAccount preferred = preferredAccount(accounts, authenticated);
                this.clearSelection();
                this.finish(accounts, preferred);
            });
        });
    }

    /**
     * 一次建多个账号时切到哪个：优先服务端原本就绑定的那个角色——下次登录本来就会用它，
     * 换成别的等于替用户做了一次「换角色」；没有绑定记录时退到勾选的第一个。
     */
    private static BaseAccount preferredAccount(List<? extends BaseAccount> accounts, LoginDraft draft) {
        Profile bound = draft.bound();
        if (bound != null) {
            String uuid = normalize(bound.playerUuid());
            for (BaseAccount account : accounts) {
                if (normalize(account.getAccountStorage().getPlayerUUID().toString()).equals(uuid)) {
                    return account;
                }
            }
        }
        return accounts.get(0);
    }

    /** UUID 的连字符与大小写在服务端 / 本地之间并不统一，比较前统一成小写无连字符。 */
    private static String normalize(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private void copyLink(DeviceCode code) {
        String url = code.browserUrl();
        Platforms.getMinecraftPlatform().copyText(url == null ? code.verificationUri() : url);
    }

    /** 当前模式的底色标记：按钮本身没有 selected 样式，用与左侧筛选栏相同的背景带表达选中。 */
    private int modeX() {
        return this.oauthMode ? this.contentX() : this.contentX() + TOGGLE_W + PAD;
    }

    private int modeWidth() {
        return this.oauthMode ? TOGGLE_W : INPUT_W - TOGGLE_W - PAD;
    }

    /** OAuth 提示行：动作按钮下沿再空一格，和微软那屏同一条规则。 */
    private int oauthHintY() {
        int actionTop = this.contentTop();
        if (this.oauthAvailable) {
            actionTop += BTN_H + PAD;
        }
        return hintBelow(actionTop + BTN_H);
    }

    /** 选中模式的外圈：必须先于控件绘制，否则会盖住按钮本身。 */
    private void drawModeFrame(GuiGraphics context) {
        if (!this.oauthAvailable) {
            return;
        }

        int top = this.contentTop();
        context.fill(this.modeX() - SelectionStyle.INSET_X, top - SelectionStyle.INSET_Y,
                this.modeX() + this.modeWidth() + SelectionStyle.INSET_X, top + BTN_H + SelectionStyle.INSET_Y,
                SelectionStyle.FRAME);
    }

    /**
     * 选择态的头像：画在每个角色按钮的左侧。
     *
     * <p>按钮文字居中，左侧留白正好放头像；纹理只注册一次并按住皮肤缓存键复用，翻页重绘不重复注册。</p>
     */
    private void drawCandidateAvatars(GuiGraphics context) {
        List<Profile> list = this.candidates;
        if (list == null || this.candidateAvatars.isEmpty()) {
            return;
        }
        int capacity = this.rowCapacity();
        int pages = this.pageCount();
        int start = Math.min(this.candidatePage, pages - 1) * capacity;
        int top = this.selectionRowsTop(capacity);
        int x = this.contentX();
        for (int i = 0; i < capacity; i++) {
            int index = start + i;
            if (index >= list.size()) {
                return;
            }
            int[] pixels = this.avatarPixels(list.get(index));
            if (pixels != null) {
                this.drawAvatar(context, x + AVATAR_INSET, top + i * (BTN_H + PAD) + AVATAR_INSET, pixels);
            }
        }
    }

    /** 取头像像素网格；缓存里没有就返回 null，界面只画按钮文字。 */
    private int[] avatarPixels(Profile profile) {
        String key = this.candidateAvatars.get(profile.playerUuid());
        if (key == null) {
            return null;
        }
        int[] cached = this.avatarTextures.get(key);
        if (cached != null) {
            return cached;
        }
        int[] pixels = AvatarPixels.decode(AvatarCache.load(key), AVATAR_SIZE);
        if (pixels != null) {
            this.avatarTextures.put(key, pixels);
        }
        return pixels;
    }

    /** 用矩形填充逐像素画头像：这套绘制调用是 1.20 / 1.21.x / 26.x 唯一一致的写法。 */
    private void drawAvatar(GuiGraphics context, int left, int top, int[] pixels) {
        for (int y = 0; y < AVATAR_SIZE; y++) {
            for (int x = 0; x < AVATAR_SIZE; x++) {
                int argb = pixels[y * AVATAR_SIZE + x];
                if ((argb >>> 24) == 0) {
                    continue;
                }
                context.fill(left + x, top + y, left + x + 1, top + y + 1, argb);
            }
        }
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        this.drawHeader(context);

        if (this.candidates != null) {
            // 选择态没有模式按钮和密码框，别画它们的外圈与遮蔽；这一屏也不输密码，
            // 明文警告让位给「请选择角色」，两条说明共用页头下沿那一行。
            super.render(context, mouseX, mouseY, delta);
            this.drawCandidateAvatars(context);
            this.center(context, this.selectionHint(), this.noticeY(), 0xFFE0E0E0);
            this.drawStatus(context);
            return;
        }

        if (ServerAddressSecurity.isPlaintext(this.server.apiRoot())) {
            this.center(context, Component.translatable("accountsx.account.server.insecure"), this.noticeY(), 0xFFFF5555);
        }

        this.drawModeFrame(context);
        super.render(context, mouseX, mouseY, delta);

        this.drawPasswordMask(context, this.passwordBox, this.passwordX, this.passwordY);

        if (this.oauthMode) {
            this.center(context, this.oauthHint(), this.oauthHintY(), 0xFFE0E0E0);
        }

        this.drawStatus(context);
    }

    /** 选择态提示：说明在选角色，角色多时补上页码（翻页按钮本身没有文字位）。 */
    private Component selectionHint() {
        Component hint = Component.translatable("accountsx.account.external.select_profile");
        int pages = this.pageCount();
        if (pages <= 1) {
            return hint;
        }
        return hint.copy().append(Component.literal(" " + (this.candidatePage + 1) + "/" + pages));
    }

    /** OAuth 提示：没有设备码时说明下一步，有设备码时展示验证码与地址。 */
    private Component oauthHint() {
        DeviceCode code = this.deviceCode;
        if (code == null) {
            return this.oauthAvailable
                    ? Component.translatable("accountsx.account.external.hint.oauth")
                    : Component.translatable("accountsx.account.external.oauth_unavailable");
        }
        // 链接不占版面：用下面的「打开浏览器」「复制登录链接」按钮代替。
        return Component.translatable("accountsx.account.external.code", code.userCode());
    }
}
