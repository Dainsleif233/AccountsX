package top.syshub.accountsx.image;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * AWT / ImageIO implementation of {@link AvatarRenderer}.
 *
 * <p>Moved verbatim from the old core {@code AvatarUtils.drawAvatar} (P1.4):
 * crops the 8x8 face region {@code (8,8)} and the 8x8 hat layer {@code (40,8)}
 * from a 64x64 (or higher, scaled) skin into a 64x64 avatar with a small
 * margin and nearest-neighbor scaling to preserve the pixel look.</p>
 *
 * <p>This class references {@code java.awt} / {@code javax.imageio} and
 * therefore must remain outside core (enforced by {@code :checkArchitecture}).</p>
 */
public final class AwtAvatarRenderer implements AvatarRenderer {

    public static final AwtAvatarRenderer INSTANCE = new AwtAvatarRenderer();

    private AwtAvatarRenderer() {
    }

    @Override
    public byte[] renderAvatar(byte[] skinPng) throws IOException {
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(skinPng));
        if (skin == null) {
            throw new IOException("Failed to decode skin image");
        }

        int scale = Math.max(1, skin.getWidth() / 64);
        int faceOffset = (int) Math.round(64 / 18.0);

        // Target image with alpha so the margin stays transparent.
        BufferedImage avatar = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = avatar.createGraphics();

        // Keep the pixel-art look — no smoothing.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        // Clear to transparent.
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, 64, 64);
        g.setComposite(AlphaComposite.SrcOver);

        // Base face (8,8,8,8) drawn into the margin-padded region.
        int sxFace = 8 * scale, syFace = 8 * scale, swFace = 8 * scale, shFace = 8 * scale;
        int dwFace = 64 - 2 * faceOffset, dhFace = 64 - 2 * faceOffset;
        g.drawImage(skin, faceOffset, faceOffset, faceOffset + dwFace, faceOffset + dhFace,
                sxFace, syFace, sxFace + swFace, syFace + shFace, null);

        // Hat / hair overlay (40,8,8,8) drawn across the full avatar.
        int sxHat = 40 * scale, syHat = 8 * scale, swHat = 8 * scale, shHat = 8 * scale;
        g.drawImage(skin, 0, 0, 64, 64,
                sxHat, syHat, sxHat + swHat, syHat + shHat, null);

        g.dispose();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(avatar, "PNG", out);
            return out.toByteArray();
        }
    }
}
