package com.qb20nh.cbbg.render.stbn;

import static org.junit.jupiter.api.Assertions.*;

import com.qb20nh.cbbg.api.NoiseVolume;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class STBNGeneratorTest {
  @Test
  void forcedGenerationBypassesValidCacheAndMatchingEarlyWork() throws Exception {
    AtomicInteger checks = new AtomicInteger();
    BooleanSupplier cacheValid =
        () -> {
          checks.incrementAndGet();
          return true;
        };
    assertNull(STBNGenerator.prepareEarly(4, 4, 2, 0L, cacheValid).get(10, TimeUnit.SECONDS));
    var fields =
        Objects.requireNonNull(
            STBNGenerator.generateAsync(4, 4, 2, 0L, true, cacheValid).get(10, TimeUnit.SECONDS));
    assertEquals(4, fields.volume().width());
    assertEquals(4, fields.volume().height());
    assertEquals(2, fields.volume().depth());
    assertEquals(0L, fields.seed());
    assertEquals(1, checks.get());
    assertNull(
        STBNGenerator.generateAsync(4, 4, 2, 0L, false, cacheValid).get(10, TimeUnit.SECONDS));
    assertEquals(2, checks.get());
  }

  @Test
  void fieldsPreserveImmutableVolumeAndCapturedSeed() {
    NoiseVolume volume = NoiseVolume.generate(2, 2, 1, 101L);
    var fields = new STBNGenerator.STBNFields(volume, 101L);
    assertSame(volume, fields.volume());
    assertEquals(101L, fields.seed());
    byte[] expected = volume.frameRGBA(0);
    byte[] copy = fields.volume().frameRGBA(0);
    copy[0] ^= 0xff;
    assertArrayEquals(expected, fields.volume().frameRGBA(0));
  }

  @Test
  void firstMatchingRequestClaimsEarlyWorkOnlyOnce() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger checks = new AtomicInteger();
    try {
      CompletableFuture<STBNGenerator.@Nullable STBNFields> early =
          STBNGenerator.prepareEarly(
              4,
              4,
              2,
              101L,
              () -> {
                checks.incrementAndGet();
                started.countDown();
                return await(release, null);
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      CompletableFuture<STBNGenerator.@Nullable STBNFields> claimed =
          STBNGenerator.generateAsync(
              4,
              4,
              2,
              101L,
              () -> {
                fail("Matching work started twice");
                return false;
              });
      assertSame(claimed, STBNGenerator.get());
      release.countDown();
      STBNGenerator.STBNFields fields = Objects.requireNonNull(claimed.get(10, TimeUnit.SECONDS));
      assertSame(fields, early.get(10, TimeUnit.SECONDS));
      assertEquals(101L, fields.seed());
      assertArrayEquals(
          NoiseVolume.generate(4, 4, 2, 101L).frameRGBA(0), fields.volume().frameRGBA(0));
      assertEquals(1, checks.get());

      assertNull(STBNGenerator.generateAsync(4, 4, 2, 101L, () -> true).get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
  }

  @Test
  void cacheHitIsRecheckedAfterEarlyWorkIsClaimed() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger rechecks = new AtomicInteger();
    try {
      CompletableFuture<STBNGenerator.@Nullable STBNFields> early =
          STBNGenerator.prepareEarly(
              4,
              4,
              2,
              102L,
              () -> {
                started.countDown();
                await(release, null);
                return true;
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      CompletableFuture<STBNGenerator.@Nullable STBNFields> claimed =
          STBNGenerator.generateAsync(
              4,
              4,
              2,
              102L,
              () -> {
                rechecks.incrementAndGet();
                return false;
              });
      release.countDown();
      assertNull(early.get(10, TimeUnit.SECONDS));
      assertEquals(102L, Objects.requireNonNull(claimed.get(10, TimeUnit.SECONDS)).seed());
      assertEquals(1, rechecks.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void changedSeedCancelsEarlyWorkAndChecksItsOwnCache() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    AtomicInteger replacementChecks = new AtomicInteger();
    try {
      CompletableFuture<STBNGenerator.@Nullable STBNFields> early =
          STBNGenerator.prepareEarly(
              4,
              4,
              2,
              103L,
              () -> {
                started.countDown();
                await(release, interrupted);
                return false;
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      CompletableFuture<STBNGenerator.@Nullable STBNFields> replacement =
          STBNGenerator.generateAsync(
              4,
              4,
              2,
              104L,
              () -> {
                replacementChecks.incrementAndGet();
                return true;
              });
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
      assertTrue(early.isCancelled());
      assertNull(replacement.get(10, TimeUnit.SECONDS));
      assertEquals(1, replacementChecks.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void supersedingAClaimCancelsItsCacheRecheck() throws Exception {
    CountDownLatch rechecking = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    try {
      assertNull(STBNGenerator.prepareEarly(4, 4, 2, 105L, () -> true).get(10, TimeUnit.SECONDS));
      CompletableFuture<STBNGenerator.@Nullable STBNFields> claimed =
          STBNGenerator.generateAsync(
              4,
              4,
              2,
              105L,
              () -> {
                rechecking.countDown();
                await(release, interrupted);
                return false;
              });
      assertTrue(rechecking.await(5, TimeUnit.SECONDS));
      CompletableFuture<STBNGenerator.@Nullable STBNFields> replacement =
          STBNGenerator.generateAsync(4, 4, 2, 106L, () -> true);
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
      assertTrue(claimed.isCancelled());
      assertNull(replacement.get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
  }

  private static boolean await(CountDownLatch release, @Nullable CountDownLatch interrupted) {
    try {
      release.await();
      return false;
    } catch (InterruptedException e) {
      if (interrupted != null) interrupted.countDown();
      Thread.currentThread().interrupt();
      return true;
    }
  }
}
