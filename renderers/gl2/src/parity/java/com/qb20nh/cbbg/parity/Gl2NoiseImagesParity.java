package com.qb20nh.cbbg.parity;

import com.qb20nh.cbbg.math.BlueNoise;
import com.qb20nh.cbbg.render.Gl2NoiseImages;
import com.qb20nh.cbbg.render.stbn.NoiseCache;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;

/** CPU/cache checks on the actual Java 8 runtime, with the historical seed constants as oracle. */
public final class Gl2NoiseImagesParity {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path report = Paths.get(args[0]);
        Path cache = Files.createTempDirectory(report.getParent(), "generated-noise-");
        Path first = cache.resolve(String.format(NoiseCache.IMAGE_BASE_FMT, 4, 4, 2, 0) + ".png");
        try {
            NoiseCache validator = new NoiseCache(cache, (message, failure) -> { throw new AssertionError(message, failure); });
            BufferedImage[] generated = Gl2NoiseImages.loadOrGenerate(cache, 4, 2, 0);
            require(validator.isCacheValid(4, 4, 2, 0), "Generated cache is invalid");
            double[] u = BlueNoise.generateScalarField(4, 4, 2, 1234);
            double[] v = BlueNoise.generateScalarField(4, 4, 2, 5678);
            for (int frame = 0; frame < 2; frame++) {
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        int index = (frame * 4 + y) * 4 + x;
                        int expected = BlueNoise.calculatePixelColor(u[index], v[index]);
                        int actual = generated[frame].getRGB(x, y);
                        require((actual >>> 16 & 255) == (expected & 255), "Red channel differs from historical generator");
                        require((actual >>> 8 & 255) == (expected >>> 8 & 255), "Green channel differs");
                        require((actual & 255) == (expected >>> 16 & 255), "Blue channel differs");
                        require(actual >>> 24 == 255, "Generated alpha is not opaque");
                    }
                }
            }
            FileTime marker = FileTime.fromMillis(1000);
            Files.setLastModifiedTime(first, marker);
            equal(generated, Gl2NoiseImages.loadOrGenerate(cache, 4, 2, 0));
            require(Files.getLastModifiedTime(first).equals(marker), "Warm cache was rewritten");
            equal(generated, Gl2NoiseImages.generate(cache, 4, 2, 0));
            require(!Files.getLastModifiedTime(first).equals(marker), "Explicit regeneration reused cached PNG");
            Files.write(first, new byte[] {0});
            require(!validator.isCacheValid(4, 4, 2, 0), "Corrupt PNG accepted");
            equal(generated, Gl2NoiseImages.loadOrGenerate(cache, 4, 2, 0));
            Gl2NoiseImages.loadOrGenerate(cache, 4, 2, 1);
            require(validator.isCacheValid(4, 4, 2, 1), "Replacement seed not saved");
            require(!validator.isCacheValid(4, 4, 2, 0), "Old seed still accepted");
            Thread.currentThread().interrupt();
            try {
                Gl2NoiseImages.loadOrGenerate(cache, 4, 2, 1);
                throw new AssertionError("Interrupted generation returned normally");
            } catch (InterruptedException expected) {
                checks++;
            } finally {
                Thread.interrupted();
            }
            String json = "{\"passed\":true,\"checks\":" + checks + "}";
            Files.write(report, json.getBytes(StandardCharsets.UTF_8));
            System.out.println(json);
        } finally {
            for (int frame = 0; frame < 2; frame++) {
                Files.deleteIfExists(cache.resolve(String.format(NoiseCache.IMAGE_BASE_FMT, 4, 4, 2, frame) + ".png"));
            }
            Files.deleteIfExists(cache.resolve(String.format(NoiseCache.HASH_FILE_FMT, 4, 4, 2)));
            Files.deleteIfExists(cache);
        }
    }

    private static void equal(BufferedImage[] expected, BufferedImage[] actual) {
        require(actual != null && actual.length == expected.length, "Missing decoded frames");
        for (int frame = 0; frame < expected.length; frame++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    require(expected[frame].getRGB(x, y) == actual[frame].getRGB(x, y), "Decoded cache pixels changed");
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
