package top.syshub.accountsx.common.auth.yggdrasil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.common.accounts.impl.injector.InjectorAuthException;
import top.syshub.accountsx.common.accounts.model.TokenReuse;
import top.syshub.accountsx.common.net.HttpGateway;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import top.syshub.accountsx.common.auth.server.AuthServer;
import top.syshub.accountsx.common.auth.server.AuthServerKind;

/**
 * 认证失败必须给出可操作提示，而不是「HTTP 403」或 NPE。
 *
 * <p>Yggdrasil 的失败是 200/403 + {@code error}/{@code errorMessage} 的 JSON 错误体，
 * 因此请求必须以 {@code ignoreHttpStatus=true} 发出，否则错误体读不到。</p>
 */
class YggdrasilPasswordFlowTest {

    private static final String API_ROOT = "https://ygg.invalid/api/yggdrasil";
    private static final AuthServer SERVER =
            new AuthServer(API_ROOT, AuthServerKind.YGGDRASIL, "FakeInjector", null, 0L);

    /** 返回固定响应、并记录最后一次请求的假网关。 */
    private static final class FakeGateway implements HttpGateway {
        private JsonObject response;
        private JsonElement lastBody;
        private String lastUrl;
        private boolean lastIgnoreHttpStatus;

        FakeGateway(JsonObject response) {
            this.response = response;
        }

        @Override
        public JsonObject get(String url) {
            throw new UnsupportedOperationException("fake: get not expected");
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("fake: get(headers) not expected");
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            return postJson(url, body, false);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            this.lastUrl = url;
            this.lastBody = body;
            this.lastIgnoreHttpStatus = ignoreHttpStatus;
            return response;
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException("fake: postForm not expected");
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException("fake: postForm(ignore) not expected");
        }

        @Override
        public Headers getHeaders(String url) {
            throw new UnsupportedOperationException("fake: getHeaders not expected");
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }
    }

    /** 按 URL 分派响应、并按 URL 记录请求体的假网关：一条登录流程会打两个端点。 */
    private static final class RoutingGateway implements HttpGateway {
        private final Map<String, JsonObject> responses = new LinkedHashMap<>();
        private final Map<String, JsonElement> bodies = new LinkedHashMap<>();
        private final Set<String> empties = new HashSet<>();
        /** 按 URL 指定状态码；未指定的按「有响应体 → 200，空响应体 → 204」推断。 */
        private final Map<String, Integer> statuses = new LinkedHashMap<>();

        RoutingGateway reply(String path, JsonObject response) {
            this.responses.put(API_ROOT + path, response);
            return this;
        }

        /** 204 No Content：真网关把空响应体解析成 null（validate 成功就是这个形状）。 */
        RoutingGateway replyEmpty(String path) {
            this.empties.add(API_ROOT + path);
            return this;
        }

        /** 指定状态码（validate 的 403 / 502 等判定用例）。 */
        RoutingGateway replyStatus(String path, int status) {
            this.statuses.put(API_ROOT + path, status);
            return this;
        }

        /** 模拟不支持查状态码的网关（第三方实现）。 */
        private boolean withoutStatusSupport;

        RoutingGateway withoutStatusSupport() {
            this.withoutStatusSupport = true;
            return this;
        }

        private int statusOf(String url) {
            Integer explicit = this.statuses.get(url);
            if (explicit != null)
                return explicit;
            return this.empties.contains(url) ? 204 : 200;
        }

        @Override
        public Result postJsonWithStatus(String url, JsonElement body) {
            if (this.withoutStatusSupport)
                throw new UnsupportedOperationException("fake: no status");
            this.bodies.put(url, body);
            JsonObject response = this.empties.contains(url) ? null : this.responses.get(url);
            return new Result(statusOf(url), response);
        }

        @Override
        public JsonObject get(String url) {
            // 头像解析在 AvatarService 内部吞异常，这里直接拒绝即可。
            throw new UnsupportedOperationException("fake: get not expected");
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("fake: get(headers) not expected");
        }

        @Override
        public JsonObject postJson(String url, JsonElement body) {
            return postJson(url, body, false);
        }

        @Override
        public JsonObject postJson(String url, JsonElement body, boolean ignoreHttpStatus) {
            this.bodies.put(url, body);
            if (this.empties.contains(url)) {
                return null;
            }
            JsonObject response = this.responses.get(url);
            if (response == null) {
                throw new AssertionError("unexpected POST " + url);
            }
            return response;
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData) {
            throw new UnsupportedOperationException("fake: postForm not expected");
        }

        @Override
        public JsonObject postForm(String url, Map<String, String> formData, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException("fake: postForm(ignore) not expected");
        }

        @Override
        public Headers getHeaders(String url) {
            throw new UnsupportedOperationException("fake: getHeaders not expected");
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }

        JsonObject body(String path) {
            JsonElement body = this.bodies.get(API_ROOT + path);
            assertThat(body).as("no POST to " + path).isNotNull();
            return body.getAsJsonObject();
        }

        boolean posted(String path) {
            return this.bodies.containsKey(API_ROOT + path);
        }
    }

    private static JsonObject error(String error, String errorMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("error", error);
        json.addProperty("errorMessage", errorMessage);
        return json;
    }

    private static JsonObject profile(String name, String uuid) {
        JsonObject profile = new JsonObject();
        profile.addProperty("name", name);
        profile.addProperty("id", uuid);
        return profile;
    }

    private static JsonObject authenticated(String accessToken, JsonArray available) {
        JsonObject json = new JsonObject();
        json.addProperty("accessToken", accessToken);
        json.addProperty("clientToken", "fake-client-token");
        if (available != null) {
            json.add("availableProfiles", available);
        }
        return json;
    }

    private static JsonArray profiles(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return array;
    }

    @Test
    void login_errorBody_becomesActionableException() {
        FakeGateway fake = new FakeGateway(error("ForbiddenOperationException", "Invalid credentials"));

        assertThatThrownBy(() -> YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("Invalid credentials")
                .extracting(e -> ((InjectorAuthException) e).getTranslationKey())
                .isEqualTo("accountsx.account.fail.injector_login");

        // 必须忽略 HTTP 状态码，否则网关会先把 403 变成 IOException，错误体读不到。
        assertThat(fake.lastIgnoreHttpStatus).isTrue();
        assertThat(fake.lastUrl).isEqualTo(API_ROOT + "/authserver/authenticate");
        assertThat(fake.lastBody.getAsJsonObject().get("clientToken").getAsString()).hasSize(32);
    }

    @Test
    void login_responseWithoutAccessToken_isIOExceptionNotNpe() {
        // drasl 的 500 形状：只有 errorMessage，没有 error 字段 —— throwIfError 放行，取值不能 NPE。
        JsonObject body = new JsonObject();
        body.addProperty("path", "/authserver/authenticate");
        body.addProperty("errorMessage", "internal server error");
        FakeGateway fake = new FakeGateway(body);

        assertThatThrownBy(() -> YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("does not contain a string accessToken")
                .isNotInstanceOf(InjectorAuthException.class);
    }

    @Test
    void authenticate_multiProfile_returnsDraftWithoutRefreshing() throws Exception {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"),
                                profile("Steve", "22222222222222222222222222222222"))));

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");

        assertThat(draft.profiles()).extracting(YggdrasilProfiles.Profile::playerName)
                .containsExactly("Alex", "Steve");
        assertThat(draft.bound()).isNull();
        // 关键行为：认证阶段不许替用户选角色，所以不能有任何 refresh 请求。
        assertThat(fake.posted("/authserver/refresh")).isFalse();
    }

    @Test
    void bind_sendsSelectedProfileAndKeepsBoundToken() throws Exception {
        String steve = "22222222222222222222222222222222";
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"),
                                profile("Steve", steve))))
                .reply("/authserver/refresh", authenticated("fake-bound-access", null));

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");
        YggdrasilAuthResult result = YggdrasilPasswordFlow.bind(fake, SERVER, draft,
                new YggdrasilProfiles.Profile("Steve", steve));

        JsonObject body = fake.body("/authserver/refresh");
        assertThat(body.getAsJsonObject("selectedProfile").get("id").getAsString()).isEqualTo(steve);
        assertThat(body.getAsJsonObject("selectedProfile").get("name").getAsString()).isEqualTo("Steve");
        assertThat(body.get("clientToken").getAsString()).isEqualTo(draft.clientToken());
        // 绑定后的令牌才是这个角色专属的，必须用它建账号。
        assertThat(result.accessToken()).isEqualTo("fake-bound-access");
        assertThat(result.playerUuid().replace("-", "")).isEqualTo(steve);
    }

    @Test
    void single_multiProfileWithoutBound_requiresSelection() throws Exception {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"),
                                profile("Steve", "22222222222222222222222222222222"))));

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");

        assertThatThrownBy(() -> YggdrasilPasswordFlow.single(fake, SERVER, draft))
                .isInstanceOf(ProfileSelectionRequiredException.class)
                .satisfies(e -> assertThat(((ProfileSelectionRequiredException) e).getProfiles()).hasSize(2));
        // 不许「猜」一个角色回来：多角色账号必须由用户选。
        assertThat(fake.posted("/authserver/refresh")).isFalse();
    }

    @Test
    void single_singleProfile_bindsThroughRefresh() throws Exception {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access",
                        profiles(profile("Alex", "11111111111111111111111111111111"))))
                .reply("/authserver/refresh", authenticated("fake-bound-access", null));

        YggdrasilAuthResult result = YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass");

        assertThat(fake.body("/authserver/refresh").getAsJsonObject("selectedProfile")
                .get("id").getAsString()).isEqualTo("11111111111111111111111111111111");
        assertThat(result.accessToken()).isEqualTo("fake-bound-access");
    }

    @Test
    void single_serverAlreadyBound_doesNotRefreshAgain() throws Exception {
        // 服务端在认证响应里就给了 selectedProfile：令牌已经绑好了，再刷新一次纯属浪费。
        JsonObject body = authenticated("fake-access", null);
        body.add("selectedProfile", profile("Alex", "11111111111111111111111111111111"));
        RoutingGateway fake = new RoutingGateway().reply("/authserver/authenticate", body);

        YggdrasilAuthResult result = YggdrasilPasswordFlow.login(fake, SERVER, "fake-user", "fake-pass");

        assertThat(fake.posted("/authserver/refresh")).isFalse();
        assertThat(result.accessToken()).isEqualTo("fake-access");
        assertThat(result.playerName()).isEqualTo("Alex");
    }

    @Test
    void authenticate_availableProfilesWithBound_keepsBoth_forPreselection() throws Exception {
        // availableProfiles 有两个角色、同时服务端已 bound 其中一个：两个事实都要保留，
        // 界面才能列出全部角色并把已绑的那个预先勾上。
        JsonObject body = authenticated("fake-access",
                profiles(profile("Alex", "11111111111111111111111111111111"),
                        profile("Steve", "22222222222222222222222222222222")));
        body.add("selectedProfile", profile("Steve", "22222222222222222222222222222222"));
        RoutingGateway fake = new RoutingGateway().reply("/authserver/authenticate", body);

        YggdrasilPasswordFlow.LoginDraft draft =
                YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass");

        assertThat(draft.profiles()).hasSize(2);
        assertThat(draft.bound()).isNotNull();
        assertThat(draft.bound().playerName()).isEqualTo("Steve");
        assertThat(draft.isBoundTo(new YggdrasilProfiles.Profile("Steve",
                "22222222222222222222222222222222"))).isTrue();
        assertThat(draft.isBoundTo(new YggdrasilProfiles.Profile("Alex",
                "11111111111111111111111111111111"))).isFalse();
    }

    @Test
    void authenticate_noProfiles_isActionableException() {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", authenticated("fake-access", null));

        assertThatThrownBy(() -> YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("does not contain any player profile")
                .extracting(e -> ((InjectorAuthException) e).getTranslationKey())
                .isEqualTo("accountsx.account.fail.injector_login");
    }

    @Test
    void authenticate_errorWithoutMessage_fallsBackToErrorCode() {
        // 有的服务端只给 error 码不给 errorMessage：提示里必须还有东西可读，不能是空串。
        JsonObject body = new JsonObject();
        body.addProperty("error", "ForbiddenOperationException");
        RoutingGateway fake = new RoutingGateway().reply("/authserver/authenticate", body);

        assertThatThrownBy(() -> YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("ForbiddenOperationException");
    }

    @Test
    void authenticate_blankErrorMessage_fallsBackToErrorCode() {
        RoutingGateway fake = new RoutingGateway()
                .reply("/authserver/authenticate", error("ForbiddenOperationException", "   "));

        assertThatThrownBy(() -> YggdrasilPasswordFlow.authenticate(fake, SERVER, "fake-user", "fake-pass"))
                .isInstanceOf(InjectorAuthException.class)
                .hasMessageContaining("ForbiddenOperationException");
    }

    // ── B4：/authserver/validate 判定 ──

    @Test
    void validate_noContent_confirmsTokenAndSendsClientToken() throws Exception {
        RoutingGateway fake = new RoutingGateway().replyEmpty("/authserver/validate");

        TokenReuse valid = YggdrasilPasswordFlow.validate(fake, SERVER, "fake-access", "fake-client-token");

        // 只有 204（空响应体）才算明确有效——这是唯一「令牌还能用」的确定信号。
        assertThat(valid).isEqualTo(TokenReuse.YES);
        JsonObject body = fake.body("/authserver/validate");
        assertThat(body.get("accessToken").getAsString()).isEqualTo("fake-access");
        assertThat(body.get("clientToken").getAsString()).isEqualTo("fake-client-token");
    }

    @Test
    void validate_withoutClientToken_omitsTheField() throws Exception {
        RoutingGateway fake = new RoutingGateway().replyEmpty("/authserver/validate");

        assertThat(YggdrasilPasswordFlow.validate(fake, SERVER, "fake-access", "")).isEqualTo(TokenReuse.YES);
        assertThat(fake.body("/authserver/validate").has("clientToken")).isFalse();
    }

    @Test
    void validate_errorBody_isNotTreatedAsValid() throws Exception {
        // 403 + error 体：判无效，调用方退回刷新而不是带着死令牌进游戏。
        RoutingGateway fake = new RoutingGateway()
                .replyStatus("/authserver/validate", 403)
                .reply("/authserver/validate", error("ForbiddenOperationException", "Invalid token."));

        assertThat(YggdrasilPasswordFlow.validate(fake, SERVER, "fake-access", null)).isEqualTo(TokenReuse.NO);
    }

    @Test
    void validate_serverError_isUnknownNotInvalid() throws Exception {
        // 网关后面的 502 常常没有响应体：按「空即有效」会把死令牌当活的，按「空即无效」又会去换票
        // 作掉别的实例刚换好的令牌——两种都错，正确答案是「问不出来」。
        RoutingGateway fake = new RoutingGateway().replyStatus("/authserver/validate", 502);

        assertThat(YggdrasilPasswordFlow.validate(fake, SERVER, "fake-access", null)).isEqualTo(TokenReuse.UNKNOWN);
    }

    @Test
    void validate_rateLimited_isUnknownNotInvalid() throws Exception {
        RoutingGateway fake = new RoutingGateway().replyStatus("/authserver/validate", 429);

        assertThat(YggdrasilPasswordFlow.validate(fake, SERVER, "fake-access", null)).isEqualTo(TokenReuse.UNKNOWN);
    }

    @Test
    void validate_gatewayWithoutStatusSupport_isUnknown() throws Exception {
        // 没有 postJsonWithStatus 的网关（第三方实现）：无从判定，同样不能当成无效。
        RoutingGateway fake = new RoutingGateway().withoutStatusSupport().replyEmpty("/authserver/validate");

        assertThat(YggdrasilPasswordFlow.validate(fake, SERVER, "fake-access", null)).isEqualTo(TokenReuse.UNKNOWN);
    }
}
