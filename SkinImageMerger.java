package com.kodari.skinoutfitswapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

final class SkinImageMerger {
    private SkinImageMerger() {
    }

    static byte[] merge(URL originalSkinUrl, File outfitFile) throws IOException {
        URLConnection connection = originalSkinUrl.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        BufferedImage original;
        try (var input = connection.getInputStream()) {
            original = ImageIO.read(input);
        }
        BufferedImage outfit = ImageIO.read(outfitFile);
        validateSkin(original, "originalus žaidėjo skinas");
        validateSkin(outfit, "outfit.png");

        BufferedImage merged = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        merged.setRGB(0, 0, 64, 64, outfit.getRGB(0, 0, 64, 64, null, 0, 64), 0, 64);
        copyHead(original, merged, 8);
        copyHead(original, merged, 40);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(merged, "png", output)) {
            throw new IOException("Nepavyko užkoduoti sujungto skino PNG.");
        }
        return output.toByteArray();
    }

    private static void copyHead(BufferedImage original, BufferedImage merged, int headX) {
        copyRegion(original, merged, headX, 0, 8, 8);
        copyRegion(original, merged, headX + 8, 0, 8, 8);
        copyRegion(original, merged, headX - 8, 8, 8, 8);
        copyRegion(original, merged, headX, 8, 8, 8);
        copyRegion(original, merged, headX + 8, 8, 8, 8);
        copyRegion(original, merged, headX + 16, 8, 8, 8);
    }

    private static void copyRegion(BufferedImage source, BufferedImage target, int x, int y, int width, int height) {
        target.setRGB(x, y, width, height, source.getRGB(x, y, width, height, null, 0, width), 0, width);
    }

    private static void validateSkin(BufferedImage image, String name) throws IOException {
        if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
            throw new IOException(name + " privalo būti 64x64 PNG paveikslėlis.");
        }
    }
}