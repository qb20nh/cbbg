package com.qb20nh.cbbg.render.stbn;

import com.qb20nh.cbbg.api.NoiseVolume;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@NullMarked
public class STBNGeneratorTest {
  @Test
  void fieldsPreserveImmutableVolume() {
    NoiseVolume volume = NoiseVolume.generate(2, 2, 1, 101L);
    var fields = new STBNGenerator.STBNFields(volume);
    Assertions.assertSame(volume, fields.volume());
    byte[] expected = volume.frameRGBA(0);
    byte[] copy = fields.volume().frameRGBA(0);
    copy[0] ^= 0xff;
    Assertions.assertArrayEquals(expected, fields.volume().frameRGBA(0));
  }

  @Test
  void forcedGenerationProducesRequestedDimensionsAndSeed() throws Exception {
    int w = 16;
    int h = 16;
    int d = 8;
    long seed = 123456789L;
    NoiseVolume volume =
        Objects.requireNonNull(
                STBNGenerator.generateAsync(w, h, d, seed, true).get(10, TimeUnit.SECONDS))
            .volume();
    Assertions.assertEquals(w, volume.width());
    Assertions.assertEquals(h, volume.height());
    Assertions.assertEquals(d, volume.depth());
    NoiseVolume expected = NoiseVolume.generate(w, h, d, seed);
    for (int frame = 0; frame < d; frame++) {
      Assertions.assertArrayEquals(expected.frameRGBA(frame), volume.frameRGBA(frame));
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          Assertions.assertEquals(255, volume.pixelABGR(x, y, frame) >>> 24);
        }
      }
    }
  }
}
