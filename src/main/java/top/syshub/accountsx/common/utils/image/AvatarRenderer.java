package top.syshub.accountsx.common.utils.image;

import java.io.IOException;

/**
 * Renders a Minecraft face avatar from a skin PNG.
 *
 * <p>This interface lives in common's {@code top.syshub.accountsx.common.utils.image} package
 * (moved back from the standalone {@code core-image} module). Callers pass raw
 * PNG bytes and receive raw PNG bytes — no AWT types leak through this
 * boundary (P1.4 / decision D4).</p>
 */
public interface AvatarRenderer {
    /**
     * @param skinPng raw PNG bytes of the player skin
     * @return raw PNG bytes of a 64x64 avatar cropped from the skin face + hat layer
     * @throws IOException if the skin cannot be decoded or the avatar cannot be written
     */
    byte[] renderAvatar(byte[] skinPng) throws IOException;
}
