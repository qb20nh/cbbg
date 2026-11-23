package com.qb20nh.cbbg.render;

import com.qb20nh.cbbg.math.BlueNoise;
import com.qb20nh.cbbg.render.stbn.NoiseCache;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/** CPU-only legacy PNG preparation; safe to call from the generation worker. */
public final class Gl2NoiseImages {
    private static final Logger LOGGER = Logger.getLogger("cbbg");

    private Gl2NoiseImages() {}

    public static BufferedImage[] loadOrGenerate(Path directory, int size, int depth, long seed)
            throws InterruptedException {
        interrupted();
        BufferedImage[] cached = loadCache(directory, size, depth, seed);
        if (cached != null) return cached;
        return generate(directory, size, depth, seed);
    }

    /** Explicit regeneration bypasses even a valid cache without deleting unrelated entries. */
    public static BufferedImage[] generate(Path directory, int size, int depth, long seed)
            throws InterruptedException {
        interrupted();
        double[] u = BlueNoise.generateScalarField(size, size, depth, BlueNoise.stbnUSeed(seed));
        interrupted();
        double[] v = BlueNoise.generateScalarField(size, size, depth, BlueNoise.stbnVSeed(seed));
        interrupted();
        BufferedImage[] images = new BufferedImage[depth];
        for (int frame = 0; frame < depth; frame++) {
            interrupted();
            images[frame] = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int index = (frame * size + y) * size + x;
                    int abgr = BlueNoise.calculatePixelColor(u[index], v[index]);
                    int argb = abgr & 0xff00ff00 | (abgr & 255) << 16 | (abgr >>> 16 & 255);
                    images[frame].setRGB(x, y, argb);
                }
            }
        }
        save(directory, images, size, depth, seed);
        return images;
    }

    public static BufferedImage[] loadCache(Path directory, int size, int depth, long seed) {
        NoiseCache cache = new NoiseCache(directory,
                (message, failure) -> LOGGER.log(Level.WARNING, message, failure));
        if (!cache.isCacheValid(size, size, depth, seed)) return null;
        BufferedImage[] frames = new BufferedImage[depth];
        try {
            for (int frame = 0; frame < depth; frame++) {
                frames[frame] = ImageIO.read(directory.resolve(imageName(size, depth, frame)).toFile());
                if (frames[frame] == null || frames[frame].getWidth() != size || frames[frame].getHeight() != size) {
                    return null;
                }
            }
            return frames;
        } catch (IOException failure) {
            LOGGER.log(Level.WARNING, "Could not decode legacy STBN cache", failure);
            return null;
        }
    }

    private static void save(Path directory, BufferedImage[] images, int size, int depth, long seed)
            throws InterruptedException {
        try {
            Files.createDirectories(directory);
            StringBuilder manifest = new StringBuilder(NoiseCache.seedHeader(seed));
            for (int frame = 0; frame < depth; frame++) {
                interrupted();
                String name = imageName(size, depth, frame);
                Path path = directory.resolve(name);
                if (!ImageIO.write(images[frame], "png", path.toFile())) throw new IOException("PNG writer unavailable");
                manifest.append(NoiseCache.calculateSHA256(Files.readAllBytes(path))).append("  ").append(name).append('\n');
            }
            Files.write(directory.resolve(String.format(NoiseCache.HASH_FILE_FMT, size, size, depth)),
                    manifest.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | NoSuchAlgorithmException failure) {
            LOGGER.log(Level.WARNING, "Could not save legacy STBN cache", failure);
        }
    }

    private static String imageName(int size, int depth, int frame) {
        return String.format(NoiseCache.IMAGE_BASE_FMT, size, size, depth, frame) + ".png";
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("STBN generation cancelled");
    }
}
