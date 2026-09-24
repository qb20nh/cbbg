package com.qb20nh.cbbg.render.stbn;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.platform.LoaderPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Platform paths and diagnostics for the shared cache implementation. */
public final class STBNCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-cache");
    public static final Path CACHE_DIR = LoaderPlatform.gameDirectory().resolve(".cbbg");
    public static final String HASH_FILE_FMT = NoiseCache.HASH_FILE_FMT;
    public static final String IMAGE_BASE_FMT = NoiseCache.IMAGE_BASE_FMT;
    private static final NoiseCache CACHE = new NoiseCache(CACHE_DIR, LOGGER::warn);

    private STBNCache() {}

    public static boolean isCacheValid(int w, int h, int d) {
        return isCacheValid(w, h, d, CbbgConfig.get().stbnSeed());
    }

    public static boolean isCacheValid(int w, int h, int d, long seed) {
        return CACHE.isCacheValid(w, h, d, seed);
    }

    public static String calculateSHA256(byte[] data) throws NoSuchAlgorithmException {
        return NoiseCache.calculateSHA256(data);
    }
}
