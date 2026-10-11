package com.qb20nh.cbbg.api;

/** Parameters for one CPU or GPU pass. These values do not change CBBG settings. */
public final class DitherOptions {
  private final float strength;
  private final float scaleX;
  private final float scaleY;
  private final boolean demo;

  public DitherOptions(float strength, float scaleX, float scaleY, boolean demo) {
    if (!Float.isFinite(strength)
        || strength < 0
        || !Float.isFinite(scaleX)
        || scaleX <= 0
        || !Float.isFinite(scaleY)
        || scaleY <= 0) {
      throw new IllegalArgumentException(
          "Strength must be nonnegative and scales must be positive");
    }
    this.strength = strength;
    this.scaleX = scaleX;
    this.scaleY = scaleY;
    this.demo = demo;
  }

  public float strength() {
    return strength;
  }

  public float scaleX() {
    return scaleX;
  }

  public float scaleY() {
    return scaleY;
  }

  public boolean demo() {
    return demo;
  }
}
