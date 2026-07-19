package top.syshub.accountsx.core.accounts.model.context;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.PublicKey;
import java.util.List;

public record AuthSecurityContext(
        List<PublicKey> profilePropertyKeys, List<PublicKey> playerCertificateKeys,
        SkinURLVerifier skinURLVerifier
) {
    public AuthSecurityContext(List<PublicKey> profilePropertyKeys, List<PublicKey> playerCertificateKeys) {
        this(profilePropertyKeys, playerCertificateKeys, SkinURLVerifier.MOJANG_DEFAULT);
    }

    public boolean checkSkinURL(String url) {
        URI uri;
        try {
            uri = new URI(url).normalize();
        } catch (URISyntaxException ignored) {
            return true;
        }

        return !skinURLVerifier.isSkinURLSecure(uri);
    }
}
