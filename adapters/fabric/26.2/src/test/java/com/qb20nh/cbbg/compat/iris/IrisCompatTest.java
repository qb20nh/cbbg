package com.qb20nh.cbbg.compat.iris;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@NullMarked
class IrisCompatTest {

  @Test
  void isShaderPackActive_falseWhenIrisNotLoaded() {
    // In unit test runtime we do not load Iris, so this should be a safe, deterministic path.
    Assertions.assertFalse(IrisCompat.isShaderPackActive());
  }
}
