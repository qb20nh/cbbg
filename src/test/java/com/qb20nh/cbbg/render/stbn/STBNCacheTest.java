package com.qb20nh.cbbg.render.stbn;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class STBNCacheTest {

    @Test
    void calculateSHA256_matchesKnownVectors() throws NoSuchAlgorithmException {
        Assertions.assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                STBNCache.calculateSHA256(new byte[0]));

        Assertions.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                STBNCache.calculateSHA256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void isCacheValid_unlikelyDimensions_isFalse() {
        // Avoid depending on any locally generated cache by using unusual dimensions.
        Assertions.assertFalse(STBNCache.isCacheValid(17, 19, 23));
    }
}


