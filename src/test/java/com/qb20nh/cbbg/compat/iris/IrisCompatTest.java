package com.qb20nh.cbbg.compat.iris;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class IrisCompatTest {

    @Test
    void isShaderPackActive_falseWhenIrisNotLoaded() {
        // In unit test runtime we do not load Iris, so this should be a safe, deterministic path.
        Assertions.assertFalse(IrisCompat.isShaderPackActive());
    }
}



