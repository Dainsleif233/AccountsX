package top.syshub.accountsx.common.auth.yggdrasil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.syshub.accountsx.common.accounts.AccountUUID;
import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.accounts.impl.injector.AbstractInjectorAccount;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorAuthException;
import top.syshub.accountsx.common.auth.yggdrasil.YggdrasilProfiles.Profile;
import top.syshub.accountsx.common.net.HttpGateway;
import top.syshub.accountsx.common.utils.AvatarService;
import top.syshub.accountsx.common.utils.Threading;
import top.syshub.accountsx.common.auth.server.AuthServer;

/**
 * Yggdrasil 用户名/密码流程（P3.1：从 {@code AbstractInjectorAccountProvider} 拆出）。
 * apiRoot 取自注册表条目，不再每登录做一次 API-Location 解析。
 *
 * <p>登录拆成「认证 → 选角色 → 绑定」三步：{@code /authenticate} 只证明凭据有效；一个账号可能有
 * 多个角色，令牌到底属于哪个角色由 {@code /authserver/refresh} 的 {@code selectedProfile} 决定。
 * 旧实现把三步揉在一起，于是多角色账号拿空玩家名去匹配而必然失败，单角色账号又从不绑定角色
 * （刷新回来的仍是无角色的令牌）。</p>
 */
public final class YggdrasilPasswordFlow {
    /**
     * 生成 clientToken 用。drasl 自己用 {@code RandomHex(16)} 生成 32 个十六进制字符
     * （drasl {@code util.go}），Yggdrasil 规范也把 clientToken 限制在 32 字符以内，
     * 因此这里采用相同形状，避免只对 drasl 有效。
     */
    private static final SecureRandom CLIENT_TOKEN_RANDOM = new SecureRandom();

    /** 用本地 logger 而非 {@code AccountsX.LOGGER}：后者依赖 Fabric 运行时，单测类路径上加载会失败。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(YggdrasilPasswordFlow.class);

    private YggdrasilPasswordFlow() {
    }

    /**
     * 认证成功、尚未选好角色的中间结果。
     *
     * <p>{@code bound} 非空表示服务端在认证响应里直接给了 {@code selectedProfile}（账号只有一个角色时
     * 如此），令牌已经绑好，不必再刷新一次。draft 里带着 accessToken/clientToken，只在内存中传递，
     * 绝不落盘、也不进日志。</p>
     */
    public record LoginDraft(String accessToken, String clientToken, List<Profile> profiles, Profile bound) {
        /** 该 draft 的令牌是否已经绑在 {@code profile} 上。 */
        public boolean isBoundTo(Profile profile) {
            return bound != null && profile != null && bound.playerUuid().equals(profile.playerUuid());
        }
    }

    /** 生成一个 32 字符十六进制 clientToken。 */
    static String generateClientToken() {
        byte[] bytes = new byte[16];
        CLIENT_TOKEN_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 取服务端回显的 clientToken；缺省或为空时返回 {@code fallback}。
     *
     * <p>drasl 会把请求里的 clientToken 原样回显（{@code auth.go:271/393}），以服务端值为准
     * 可以对齐第三方实现可能做的规范化（例如补全/截断），避免下次刷新比对不上。</p>
     */
    private static String echoedClientToken(JsonObject json, String fallback) {
        if (json.get("clientToken") instanceof com.google.gson.JsonPrimitive echoed && echoed.isString()) {
            String value = echoed.getAsString();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return fallback;
    }

    /**
     * 统一把服务端的 Yggdrasil 错误响应转成 {@link InjectorAuthException}。
     * 注意只暴露服务端返回的 error / errorMessage，绝不带上任何令牌。
     *
     * <p>对 {@code "error"} 做类型判断而非直接 {@code getAsString()}：不规范的服务端可能把它写成
     * {@code null} 或对象，此时 {@code getAsString()} 会抛 {@link UnsupportedOperationException}，
     * 让本可操作的提示退化成「未知错误」并丢掉错误本身。</p>
     *
     * <p>i18n key 按调用场景区分：登录失败多半是用户名/密码写错（此时还没有账号可删），
     * 而刷新失败才是「凭据已失效」——沿用同一条提示会给出错误的操作建议。</p>
     */
    private static void throwIfError(JsonObject json, String translationKey) throws InjectorAuthException {
        JsonElement error = json.get("error");
        if (error == null || error.isJsonNull()) {
            return;
        }

        JsonElement errorMessage = json.get("errorMessage");
        String detail = errorMessage != null && errorMessage.isJsonPrimitive()
                ? errorMessage.getAsString().trim() : "";
        // 界面上只展示服务端自己写的说明：它已经是给用户看的语言（drasl 会本地化），
        // 而 error 码（ForbiddenOperationException 之类）是给程序看的英文类名，两个叠在一起
        // 只会把「密码错了」这条唯一有用的信息淹掉。没有说明时才退回错误码。
        throw new InjectorAuthException(translationKey, detail.isEmpty() ? primitiveText(error) : detail);
    }

    /** 把 error 字段渲染成一句文本：字符串取原值（不带 JSON 引号），其余形状保留原文。 */
    private static String primitiveText(JsonElement element) {
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    /**
     * 取响应里的 accessToken；缺失或类型不对时抛 {@link IOException} 而不是 NPE。
     *
     * <p>认证请求是忽略 HTTP 状态码发出的（否则读不到 403 的错误体），因此任何非 2xx 响应都可能
     * 流到这里。drasl 的 500 响应是 {@code {"path":…,"errorMessage":"internal server error"}} ——
     * 没有 {@code error} 字段，{@link #throwIfError} 会放行；若此时直接取值，抛出的未检查异常会
     * 绕过 {@code AccountManager.refreshAccount} 的 IOException 分支，账号永远停在「登录中」。</p>
     */
    private static String requireAccessToken(JsonObject json) throws IOException {
        JsonElement accessToken = json.get("accessToken");
        if (accessToken == null || !accessToken.isJsonPrimitive() || !accessToken.getAsJsonPrimitive().isString()) {
            // 只报告字段名，绝不把响应体拼进消息：该消息会进日志，而某些 Yggdrasil 实现的错误体
            // 会回显 accessToken/clientToken，等于把凭据写进日志（违反 AGENTS.md 的安全约束）。
            throw new IOException("Yggdrasil response does not contain a string accessToken; fields=" + json.keySet());
        }
        return accessToken.getAsString();
    }

    /**
     * 构造 {@code selectedProfile}：{@code id} 必填，{@code name} 只有知道时才带。
     *
     * <p>规范写的是 id + name，但服务端判定归属只认 id；名字未知时塞 null 反而可能被严格实现拒绝。</p>
     */
    private static JsonObject selectedProfileJson(String playerUuid, String playerName) {
        JsonObject selectedProfile = new JsonObject();
        selectedProfile.addProperty("id", playerUuid);
        if (playerName != null && !playerName.isEmpty()) {
            selectedProfile.addProperty("name", playerName);
        }
        return selectedProfile;
    }

    private static JsonObject selectedProfileJson(Profile profile) {
        return selectedProfileJson(profile.playerUuid(), profile.playerName());
    }

    /**
     * 第一阶段：用户名/密码换 accessToken，并读出候选角色。
     *
     * <p>这一步不做任何角色选择——返回值交给界面，用户勾选后再逐个 {@link #bind}。之所以把角色选择
     * 拆到外层，是因为「选哪个角色」只有在用户看到角色列表后才能决定。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static LoginDraft authenticate(HttpGateway http, AuthServer server, String username,
            String password) throws IOException {
        String apiRoot = server.apiRoot();

        JsonObject agent = new JsonObject();
        agent.addProperty("name", "Minecraft");
        agent.addProperty("version", 1);

        // 主动提供 clientToken：服务端会把它存进该 client 并在 /authserver/refresh 强制比对。
        // 若省略，drasl 会为每次登录随机生成一个（auth.go:179-192），客户端一旦丢弃，
        // 之后的刷新必然 403 Invalid token —— 账号就变成「只有第一次能用」。
        String clientToken = generateClientToken();

        JsonObject root = new JsonObject();
        root.add("agent", agent);
        root.addProperty("username", username);
        root.addProperty("password", password);
        root.addProperty("clientToken", clientToken);

        // ignoreHttpStatus=true：Yggdrasil 的认证失败是标准 JSON 错误体（drasl 一律 403 + error/errorMessage），
        // 若先被网关的状态码校验拦下，就只剩 "HTTP 403"，用户看不到真正原因（也丢了 i18n 提示）。
        JsonObject json = http.postJson(apiRoot + "/authserver/authenticate", root, true);
        throwIfError(json, "accountsx.account.fail.injector_login");

        String accessToken = requireAccessToken(json);
        Profile bound = YggdrasilProfiles.selectedProfile(json);
        List<Profile> profiles = YggdrasilProfiles.availableProfiles(json);
        if (profiles.isEmpty() && bound != null) {
            // 只有 selectedProfile 没有 availableProfiles 是常见形态（服务端已经替用户选好），
            // 此时候选集就是那一个角色。
            profiles = List.of(bound);
        }
        if (profiles.isEmpty()) {
            // 令牌有效却一个角色都没有，说明该账号在服务端还没有玩家档案；继续下去只会建出
            // 一个没有 playerUUID 的账号。
            throw new InjectorAuthException("accountsx.account.fail.injector_login",
                    "Yggdrasil response does not contain any player profile.");
        }

        return new LoginDraft(accessToken, echoedClientToken(json, clientToken), List.copyOf(profiles), bound);
    }

    /**
     * 第二阶段：把令牌绑定到指定角色。
     *
     * <p>Yggdrasil 规范里没有「选角色」端点，唯一的做法是对 {@code /authserver/refresh} 带
     * {@code selectedProfile} 再刷新一次，服务端据此把令牌绑到该角色并返回绑定后的 accessToken。
     * 这也是旧实现的缺口：不带 selectedProfile 时服务端只会回未绑定角色，随后无论刷新多少次，
     * 账号都拿不到确定的角色。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult bind(HttpGateway http, AuthServer server, LoginDraft draft,
            Profile selected) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("accessToken", draft.accessToken());
        root.addProperty("clientToken", draft.clientToken());
        root.add("selectedProfile", selectedProfileJson(selected));

        // 同认证：要读得到 403 的 error 体，才能区分「令牌过期」与「角色不存在」。
        JsonObject json = http.postJson(server.apiRoot() + "/authserver/refresh", root, true);
        throwIfError(json, "accountsx.account.fail.injector_invalid_token");

        String accessToken = requireAccessToken(json);
        return withAvatar(http, server, selected, accessToken, echoedClientToken(json, draft.clientToken()));
    }

    /** 服务端已经把令牌绑在该角色上（认证响应带回 {@code selectedProfile}）时，直接组装结果。 */
    public static YggdrasilAuthResult resultFor(HttpGateway http, AuthServer server, LoginDraft draft,
            Profile selected) {
        return withAvatar(http, server, selected, draft.accessToken(), draft.clientToken());
    }

    /**
     * 无界面调用方的收尾：绑定唯一角色，多于一个角色时抛 {@link ProfileSelectionRequiredException}。
     *
     * <p>不做「按玩家名猜」：旧实现拿空字符串去匹配角色名，多角色账号必然抛
     * {@code PlayerNoLongerExistedException}，提示「找不到玩家」——真正原因是没让用户选。</p>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult single(HttpGateway http, AuthServer server, LoginDraft draft)
            throws IOException {
        Profile profile;
        if (draft.bound() != null) {
            profile = draft.bound();
        } else if (draft.profiles().size() == 1) {
            profile = draft.profiles().get(0);
        } else {
            throw new ProfileSelectionRequiredException(draft.profiles());
        }

        return draft.isBoundTo(profile)
                ? resultFor(http, server, draft, profile)
                : bind(http, server, draft, profile);
    }

    /** 单角色快捷入口：认证 + 自动选角色 + 绑定。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static YggdrasilAuthResult login(HttpGateway http, AuthServer server, String username,
            String password) throws IOException {
        return single(http, server, authenticate(http, server, username, password));
    }

    private static YggdrasilAuthResult withAvatar(HttpGateway http, AuthServer server, Profile profile,
            String accessToken, String clientToken) {
        String profileUrl = server.apiRoot() + "/sessionserver/session/minecraft/profile/";
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUuid());
        return new YggdrasilAuthResult(accessToken, profile.playerName(), profile.playerUuid(),
                null, null, null, clientToken, avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt(),
                // 会话令牌没有 expires_in；有效性靠 /authserver/validate 确认。
                0L);
    }

    /** 密码流刷新：沿用旧语义——token 轮换写回 loginToken，多 profile 时按 preferred 匹配并刷头像。 */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static void refresh(HttpGateway http, AbstractInjectorAccount account) throws IOException {
        String apiRoot = account.getServer();
        String refreshUrl = apiRoot + "/authserver/refresh";
        String profileUrl = apiRoot + "/sessionserver/session/minecraft/profile/";

        String clientToken = account.getClientToken();
        if (clientToken == null || clientToken.isEmpty()) {
            // 旧版本（2.0.0-beta.3 及更早）登录时未保存 clientToken，且当时也没有保存密码，
            // 无法凭现有载荷补出能通过服务端比对的值；只能让用户重新添加该账号。
            //
            // 已用真机（drasl 4.0.1）确认没有更省事的退路：refresh 省略 clientToken 与发送空字符串
            // 都稳定返回 403 ForbiddenOperationException: Invalid token，只有携带正确值才 200。
            // 因此这里直接给出可操作提示，不再白发一次注定失败的请求。
            throw new InjectorAuthException(
                    "accountsx.account.fail.injector_invalid_token",
                    "This injector account has no clientToken (created by an older version); add it again."
            );
        }

        JsonObject root = new JsonObject();
        root.addProperty("accessToken", account.getLoginToken());
        root.addProperty("clientToken", clientToken);

        // 带上 selectedProfile：服务端据此把轮换后的令牌继续绑在「这个账号的玩家」上。
        // 不带的话，多角色账号刷新回来的令牌与角色脱钩，下一次刷新只能靠猜测角色。
        String preferredPlayerUUID = account.getPreferredPlayerUUID();
        if (preferredPlayerUUID != null && !preferredPlayerUUID.isEmpty()) {
            root.add("selectedProfile", selectedProfileJson(preferredPlayerUUID,
                    account.getAccountStorage().getPlayerName()));
        }

        // 同上：必须读得到 403 的错误体，才能把「凭据已失效」这类可操作原因告诉用户。
        JsonObject json = http.postJson(refreshUrl, root, true);
        throwIfError(json, "accountsx.account.fail.injector_invalid_token");

        String accessToken = requireAccessToken(json);
        String echoed = echoedClientToken(json, clientToken);
        if (!echoed.equals(clientToken)) {
            account.setClientToken(echoed);
        }

        // 关键顺序：**立刻**提交服务端已经轮换好的令牌，再去解析角色。
        // 刷新一旦被服务端接受，旧 accessToken 就已经作废（drasl 递增版本号）；若此时因为角色解析
        // 失败而把新令牌丢掉，账号会永久卡在「旧令牌已死、新令牌没保存」，只能重新添加。
        // 角色解析失败因此降级为「保留旧档案 + 记一条警告」，不再影响令牌落地。
        account.setLoginProfile(accessToken, account.getPreferredPlayerUUID());
        account.setProfile(accessToken, account.getAccountStorage().getPlayerName(), account.getAccountStorage().getPlayerUUID());

        YggdrasilProfiles.Profile profile = resolveRefreshedProfile(account, json);
        if (profile == null) {
            return;
        }

        account.setLoginProfile(accessToken, profile.playerUuid());
        account.setProfile(accessToken, profile.playerName(), AccountUUID.parse(profile.playerUuid()));

        // 头像属于可选附加信息，失败由 AvatarService 自行吞掉。
        AvatarService.AvatarKey avatar = AvatarService.fetch(http, profileUrl, profile.playerUuid());
        account.setAvatar(avatar == null ? null : avatar.key(), avatar == null ? 0L : avatar.cachedAt());
    }

    /**
     * 用 {@code /authserver/validate} 判断现有 accessToken 还能不能用（B4）。
     *
     * <p>规范：成功是 {@code 204 No Content}（无响应体），失败是 {@code 403} + error 体。与
     * {@link #refresh} 的关键区别是它**不轮换令牌**——启动时该走这条：令牌还有效就不要重新签发，
     * 否则同一账号在别的客户端/实例上会立刻失效（drasl 刷新即作废旧 accessToken）。</p>
     *
     * <p><b>必须看状态码，不能只看响应体。</b>规范说 204 有效、403 + error 体无效，但「空响应体」
     * 本身不是有效性证据：负载均衡后面的 502/504 常常没有响应体，按「空即有效」处理会让账号带着
     * 一个已死的令牌进游戏。因此把结论分成三档：</p>
     * <ul>
     *   <li>{@code 2xx} 且无错误体 → {@link TokenReuse#YES}（个别实现回 200 + 空对象，同样算确认）；</li>
     *   <li>{@code 4xx}（401/403 等）或 2xx 带 error 体 → {@link TokenReuse#NO}：服务端明确否认；</li>
     *   <li>{@code 5xx} / 429 / 408、或网关压根不支持查状态码 → {@link TokenReuse#UNKNOWN}：
     *       这时刷新会把另一个实例刚换好的令牌当场作废，宁可等下一轮。</li>
     * </ul>
     */
    @Threading.Thread(Threading.ThreadRole.WORKER)
    public static TokenReuse validate(HttpGateway http, AuthServer server, String accessToken, String clientToken)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("accessToken", accessToken);
        // clientToken 可以不带：服务端只在登录时收到过才要求比对。带上则精确复现登录上下文。
        if (clientToken != null && !clientToken.isEmpty()) {
            root.addProperty("clientToken", clientToken);
        }

        HttpGateway.Result result;
        try {
            result = http.postJsonWithStatus(server.apiRoot() + "/authserver/validate", root);
        } catch (UnsupportedOperationException e) {
            // 假网关、或第三方实现的网关没有这个方法：问不出来，别拿它当「无效」去换票。
            return TokenReuse.UNKNOWN;
        }
        return classify(result);
    }

    /**
     * 把 validate 的响应归入三档。
     *
     * <p>抽出来是为了让设备码流程的 userinfo 校验共用同一套判据——两个端点用状态码表达的
     * 语义完全一致（2xx 有效 / 4xx 无效 / 5xx 问不出来）。</p>
     */
    static TokenReuse classify(HttpGateway.Result result) {
        if (result.isTransient())
            return TokenReuse.UNKNOWN;
        if (!result.isSuccess())
            return TokenReuse.NO;
        JsonObject body = result.body();
        // 2xx 也可能带 Yggdrasil 错误体（个别实现回 200 + {error}）：那同样是否认。
        return body == null || body.isEmpty() ? TokenReuse.YES : TokenReuse.NO;
    }

    /**
     * 解析刷新响应里的角色；解析不出来时保留账号原有档案并记警告（返回 {@code null}）。
     * 刷新本身已经成功，绝不能因为角色信息异常把整次刷新判为失败。
     */
    private static YggdrasilProfiles.Profile resolveRefreshedProfile(AbstractInjectorAccount account, JsonObject json) {
        try {
            List<YggdrasilProfiles.Profile> profiles = YggdrasilProfiles.readProfiles(json);
            if (profiles.size() == 1) {
                return profiles.get(0);
            }

            String preferredPlayerUUID = account.getPreferredPlayerUUID();
            for (YggdrasilProfiles.Profile profile : profiles) {
                if (profile.playerUuid().equals(preferredPlayerUUID)) {
                    return profile;
                }
            }

            // 服务端不再返回首选角色（例如角色已被删除、或服务端只回 selectedProfile）。
            LOGGER.warn("Refreshed profile list does not contain the preferred player {}; keeping the previous profile.", preferredPlayerUUID);
            return null;
        } catch (RuntimeException e) {
            // 解析失败（字段缺失、类型不对等）不再影响令牌落地。
            LOGGER.warn("Cannot resolve the refreshed profile; keeping the previous one.", e);
            return null;
        }
    }
}
