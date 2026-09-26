package com.qb20nh.cbbg.math;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NoisePreparationTest {
  @Test
  void startupAndRendererReuseTheSameJob() throws Exception {
    AtomicInteger checks = new AtomicInteger();
    try (NoisePreparation preparation = new NoisePreparation()) {
      CompletableFuture<NoisePreparation.Fields> first =
          preparation.prepare(
              8,
              4,
              2,
              42,
              false,
              () -> {
                checks.incrementAndGet();
                return false;
              });
      ExecutorService callers = Executors.newFixedThreadPool(4);
      try {
        List<Future<CompletableFuture<NoisePreparation.Fields>>> requests = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
          requests.add(
              callers.submit(
                  () ->
                      preparation.prepare(
                          8,
                          4,
                          2,
                          42,
                          false,
                          () -> {
                            fail("Duplicate cache check");
                            return false;
                          })));
        }
        for (Future<CompletableFuture<NoisePreparation.Fields>> request : requests) {
          assertSame(first, request.get(5, TimeUnit.SECONDS));
        }
        NoisePreparation.Fields fields = first.get(5, TimeUnit.SECONDS);
        assertArrayEquals(BlueNoise.generateScalarField(8, 4, 2, 42 * 31), fields.u());
        assertArrayEquals(BlueNoise.generateScalarField(8, 4, 2, 42 * 31 + 7), fields.v());
        assertSame(first, preparation.prepare(8, 4, 2, 42, false, () -> true));
        assertEquals(1, checks.get());
      } finally {
        callers.shutdownNow();
      }
    }
  }

  @Test
  void warmCacheSkipsMathAndForceRegenerates() throws Exception {
    try (NoisePreparation preparation = new NoisePreparation()) {
      CompletableFuture<NoisePreparation.Fields> cached =
          preparation.prepare(8, 4, 2, 0, false, () -> true);
      assertNull(cached.get(5, TimeUnit.SECONDS));
      CompletableFuture<NoisePreparation.Fields> forced =
          preparation.prepare(
              8,
              4,
              2,
              0,
              true,
              () -> {
                fail("Forced generation must skip the cache");
                return true;
              });
      assertNotSame(cached, forced);
      assertNotNull(forced.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void changedSettingsInterruptTheOldJobAndUseTheNewSeed() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    try (NoisePreparation preparation = new NoisePreparation()) {
      CompletableFuture<NoisePreparation.Fields> old =
          preparation.prepare(
              8,
              4,
              2,
              0,
              false,
              () -> {
                started.countDown();
                try {
                  new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                  interrupted.countDown();
                  Thread.currentThread().interrupt();
                }
                return false;
              });
      assertTrue(started.await(5, TimeUnit.SECONDS));
      CompletableFuture<NoisePreparation.Fields> next =
          preparation.prepare(
              4,
              4,
              2,
              7,
              false,
              () -> {
                fail("Old cache cannot identify the new seed");
                return true;
              });
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
      assertTrue(old.isCancelled());
      assertTrue(preparation.matches(4, 4, 2, 7));
      assertFalse(preparation.matches(8, 4, 2, 0));
      assertArrayEquals(
          BlueNoise.generateScalarField(4, 4, 2, 7 * 31), next.get(5, TimeUnit.SECONDS).u());
    }
  }

  @Test
  void failuresRemainAvailableUntilExplicitRetry() throws Exception {
    try (NoisePreparation preparation = new NoisePreparation()) {
      CompletableFuture<NoisePreparation.Fields> failed =
          preparation.prepare(
              8,
              4,
              2,
              0,
              false,
              () -> {
                throw new IllegalStateException("cache unavailable");
              });
      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
      assertEquals("cache unavailable", failure.getCause().getMessage());
      assertSame(failed, preparation.prepare(8, 4, 2, 0, false, () -> false));
      assertNotNull(preparation.prepare(8, 4, 2, 0, true, () -> false).get(5, TimeUnit.SECONDS));
    }
  }
}
