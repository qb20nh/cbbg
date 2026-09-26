package com.qb20nh.cbbg.render.stbn;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NullMarked
public class STBNCache {
  private static final Logger LOGGER = LoggerFactory.getLogger("cbbg-cache");
  public static final Path CACHE_DIR = FabricLoader.getInstance().getGameDir().resolve(".cbbg");
  public static final String HASH_FILE_FMT = NoiseCache.HASH_FILE_FMT;
  public static final String IMAGE_BASE_FMT = NoiseCache.IMAGE_BASE_FMT;
  private static final NoiseCache CACHE = new NoiseCache(CACHE_DIR, LOGGER::warn);

  private STBNCache() {}

  public static boolean isCacheValid(int w, int h, int d) {
    return CACHE.isCacheValid(w, h, d);
  }

  public static String calculateSHA256(byte[] data) throws NoSuchAlgorithmException {
    return NoiseCache.calculateSHA256(data);
  }
}
