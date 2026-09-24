package com.qb20nh.cbbg.render.stbn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NoiseCacheTest {
    @TempDir Path directory;

    @Test
    void sha256MatchesKnownVector() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                NoiseCache.calculateSHA256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void existingCacheLayoutRemainsValidWithoutRewriting() throws Exception {
        NoiseCache cache = cache();
        assertFalse(cache.isCacheValid(16, 16, 8));
        byte[] manifest = createCache();
        assertTrue(cache.isCacheValid(16, 16, 8));
        assertArrayEquals(manifest, Files.readAllBytes(directory.resolve("stbn_16x16x8.sha256")));
        assertFalse(cache.isCacheValid(32, 16, 8));
        assertFalse(new NoiseCache(directory.resolve("other"), (message, error) -> fail(message))
                .isCacheValid(16, 16, 8));
    }

    @Test
    void changedAndMissingSlicesInvalidateCache() throws Exception {
        createCache();
        Files.write(directory.resolve("stbn_16x16x8_3.png"), new byte[] {99});
        assertFalse(cache().isCacheValid(16, 16, 8));
        createCache();
        Files.delete(directory.resolve("stbn_16x16x8_7.png"));
        assertFalse(cache().isCacheValid(16, 16, 8));
    }

    @Test
    void truncatedManifestAndReadFailureInvalidateCache() throws Exception {
        createCache();
        Path manifest = directory.resolve("stbn_16x16x8.sha256");
        Files.write(manifest, new byte[0]);
        assertFalse(cache().isCacheValid(16, 16, 8));
        Files.delete(manifest);
        Files.createDirectory(manifest);
        List<String> warnings = new ArrayList<>();
        assertFalse(new NoiseCache(directory, (message, error) -> warnings.add(message))
                .isCacheValid(16, 16, 8));
        assertEquals(1, warnings.size());
    }

    private NoiseCache cache() {
        return new NoiseCache(directory, (message, error) -> fail(message));
    }

    @Test
    void seedIsPartOfCacheIdentityAndUnknownLegacySeedIsRegenerated() throws Exception {
        byte[] entries = createCache();
        assertTrue(cache().isCacheValid(16, 16, 8));
        assertFalse(cache().isCacheValid(16, 16, 8, 0L));
        assertFalse(cache().isCacheValid(16, 16, 8, 42L));
        String manifest = NoiseCache.seedHeader(42L) + new String(entries, StandardCharsets.UTF_8);
        Files.write(directory.resolve("stbn_16x16x8.sha256"), manifest.getBytes(StandardCharsets.UTF_8));
        assertTrue(cache().isCacheValid(16, 16, 8, 42L));
        assertFalse(cache().isCacheValid(16, 16, 8, 0L));
        assertFalse(cache().isCacheValid(16, 16, 8, -42L));
    }

    @Test
    void malformedSeedMetadataCannotReuseFrames() throws Exception {
        byte[] entries = createCache();
        String manifest = "# seed invalid\n" + new String(entries, StandardCharsets.UTF_8);
        Files.write(directory.resolve("stbn_16x16x8.sha256"), manifest.getBytes(StandardCharsets.UTF_8));
        assertFalse(cache().isCacheValid(16, 16, 8, 0L));
    }

    private byte[] createCache() throws Exception {
        StringBuilder manifest = new StringBuilder();
        for (int z = 0; z < 8; z++) {
            String name = "stbn_16x16x8_" + z + ".png";
            byte[] bytes = {(byte) z, 42};
            Files.write(directory.resolve(name), bytes);
            manifest.append(NoiseCache.calculateSHA256(bytes).toUpperCase(Locale.ROOT))
                    .append("  ").append(name).append('\n');
        }
        byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("stbn_16x16x8.sha256"), bytes);
        return bytes;
    }
}
