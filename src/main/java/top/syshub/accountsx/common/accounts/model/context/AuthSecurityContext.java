package top.syshub.accountsx.common.accounts.model.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.PublicKey;
import java.util.List;

public record AuthSecurityContext(
        List<PublicKey> profilePropertyKeys, List<PublicKey> playerCertificateKeys,
        SkinURLVerifier skinURLVerifier
) {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthSecurityContext.class);

    public AuthSecurityContext(List<PublicKey> profilePropertyKeys, List<PublicKey> playerCertificateKeys) {
        this(profilePropertyKeys, playerCertificateKeys, SkinURLVerifier.MOJANG_DEFAULT);
    }

    /**
     * 判断给定皮肤 URL 是否应被拦截（返回 true 表示应拦截）。
     * 1.12 修复：方法名从 {@code checkSkinURL} 改为 {@code shouldBlockSkinUrl}，使语义与命名一致；
     * URI 解析失败时视为拦截并记录 debug 日志（原先 {@code ignored} 分支语义与命名相反）。
     */
    public boolean shouldBlockSkinUrl(String url) {
        URI uri;
        try {
            uri = new URI(url).normalize();
        } catch (URISyntaxException e) {
            LOGGER.debug("皮肤 URL 解析失败，按拦截处理: {}", url, e);
            return true;
        }

        return !skinURLVerifier.isSkinURLSecure(uri);
    }
}
