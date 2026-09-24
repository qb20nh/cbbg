package com.qb20nh.cbbg.gametest;

import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.DitherController;
import com.qb20nh.cbbg.render.stbn.STBNGenerator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** A cancelled result must not leave obsolete math running beside its replacement. */
public final class NoiseCancellationGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        CbbgConfig original = CbbgConfig.get();
        context.waitFor(client -> DitherController.isReady(), 600);
        context.runOnClient(client -> CbbgConfig.setMode(CbbgConfig.Mode.DISABLED));
        context.waitTicks(3);
        CompletableFuture<STBNGenerator.STBNFields> old = null;
        try {
            old = STBNGenerator.generateAsync(64, 64, 32, 913725L);
            Thread worker = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (worker == null && !old.isDone() && System.nanoTime() < deadline) {
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (generating(thread)) {
                        worker = thread;
                        break;
                    }
                }
                if (worker == null) Thread.sleep(1);
            }
            if (worker == null) {
                throw new AssertionError("Did not observe an active noise worker; cancellation was not exercised");
            }
            var replacement = STBNGenerator.generateAsync(16, 16, 8, 913726L);
            var fields = replacement.get(10, TimeUnit.SECONDS);
            if (!old.isCancelled() || fields == null || fields.seed() != 913726L
                    || fields.uField().length != 16 * 16 * 8 || fields.vField().length != 16 * 16 * 8) {
                throw new AssertionError("Replacement generation did not complete with its requested identity");
            }
            if (generating(worker)) {
                throw new AssertionError("Superseded noise math still runs after replacement completion");
            }
        } catch (Exception failure) {
            throw new AssertionError("Noise cancellation failed", failure);
        } finally {
            if (old != null) old.cancel(true);
            context.runOnClient(client -> CbbgConfig.setMode(original.mode()));
        }
        context.waitFor(client -> DitherController.isReady(), 600);
    }

    private static boolean generating(Thread thread) {
        for (StackTraceElement frame : thread.getStackTrace()) {
            if (frame.getClassName().equals("com.qb20nh.cbbg.math.BlueNoise")) return true;
        }
        return false;
    }
}
