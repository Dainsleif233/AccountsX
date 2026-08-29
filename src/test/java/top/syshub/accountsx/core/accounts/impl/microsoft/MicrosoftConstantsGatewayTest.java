package top.syshub.accountsx.core.accounts.impl.microsoft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import top.syshub.accountsx.core.accounts.model.context.AuthSecurityContext;
import top.syshub.accountsx.core.net.HttpGateway;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.3 安全网：{@link MicrosoftConstants#computeMicrosoftPublicKeys(HttpGateway)} 可用假网关驱动，
 * 验证 Microsoft 公钥获取路径的注入缝（无需真实网络，也无需 RSA 硬编码 fixture）。
 */
class MicrosoftConstantsGatewayTest {

    /** 只回应 get(url) 的假网关，其余方法一律抛出。 */
    private static final class FakeKeyGateway implements HttpGateway {
        private final JsonObject response;
        private String lastUrl;

        FakeKeyGateway(JsonObject response) {
            this.response = response;
        }

        String lastUrl() {
            return lastUrl;
        }

        @Override
        public JsonObject get(String url) {
            this.lastUrl = url;
            return response;
        }

        @Override
        public JsonObject get(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("fake: get(headers) not expected");
        }

        @Override
        public JsonObject postJson(String url, com.google.gson.JsonElement body) {
            throw new UnsupportedOperationException("fake: postJson not expected");
        }

        @Override
        public JsonObject postJson(String url, com.google.gson.JsonElement body, boolean ignoreHttpStatus) {
            throw new UnsupportedOperationException("fake: postJson(ignore) not expected");
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
        public Map<String, List<String>> head(String url) {
            throw new UnsupportedOperationException("fake: head not expected");
        }

        @Override
        public byte[] getBinary(String url) {
            throw new UnsupportedOperationException("fake: getBinary not expected");
        }
    }

    /** 构造一份带合法 RSA 公钥的 Microsoft 公钥响应（公钥在测试内生成，不硬编码）。 */
    private static JsonObject keyResponse() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        // MicrosoftConstants.parsePublicKeys 直接把 publicKey 字段当原始 base64 解码（无 PEM 头尾），
        // 与 injector 的 parseSignaturePublicKey（需 PEM 包裹）不同。
        String rawBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        JsonObject keyData = new JsonObject();
        keyData.addProperty("publicKey", rawBase64);
        JsonArray keys = new JsonArray();
        keys.add(keyData);
        JsonObject response = new JsonObject();
        response.add("profilePropertyKeys", keys);
        response.add("playerCertificateKeys", keys);
        return response;
    }

    @Test
    void computeMicrosoftPublicKeys_usesInjectedGateway() throws Exception {
        FakeKeyGateway fake = new FakeKeyGateway(keyResponse());

        AuthSecurityContext ctx = MicrosoftConstants.computeMicrosoftPublicKeys(fake);

        assertThat(ctx.profilePropertyKeys()).hasSize(1);
        assertThat(ctx.playerCertificateKeys()).hasSize(1);
        // 打到 Microsoft 公钥端点（SERVICES + "/publickeys"），且未触发真实网络。
        assertThat(fake.lastUrl()).endsWith("/publickeys");
    }
}
