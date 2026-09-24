package com.qb20nh.cbbg.gametest;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Screenshot;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;

/** Also runnable without CBBG as a control for native surface resize failures. */
public final class WindowResizeGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        for (int iteration = 0; iteration < 5; iteration++) {
            context.getInput().resizeWindow(854, 480);
            context.waitTicks(10);
            context.getInput().resizeWindow(960, 540);
            context.waitTicks(10);
        }
        boolean originalFullscreen = context.computeOnClient(client ->
                (SDLVideo.SDL_GetWindowFlags(client.getWindow().handle())
                        & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0);
        try {
            for (boolean fullscreen : new boolean[] {true, false, true, false}) {
                context.runOnClient(client -> {
                    client.getWindow().setFullscreen(fullscreen);
                    client.getWindow().updateFullscreenIfChanged();
                });
                context.waitFor(client -> ((SDLVideo.SDL_GetWindowFlags(client.getWindow().handle())
                        & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0) == fullscreen, 200);
                if (!fullscreen) {
                    context.getInput().resizeWindow(960, 540);
                }
                context.waitTicks(5);
                // Fabric suppresses native resize callbacks and retains simulated
                // dimensions. Synchronize them to SDL's actual surface before checking.
                int[] nativeSize = context.computeOnClient(client -> pixelSize(client.getWindow().handle()));
                context.getInput().resizeWindow(nativeSize[0], nativeSize[1]);
                context.waitFor(client -> {
                    var window = client.getWindow();
                    boolean actual = (SDLVideo.SDL_GetWindowFlags(window.handle())
                            & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0;
                    var target = client.gameRenderer.mainRenderTarget();
                    int[] pixels = pixelSize(window.handle());
                    return actual == fullscreen && window.getWidth() > 0 && window.getHeight() > 0
                            && window.getWidth() == pixels[0] && window.getHeight() == pixels[1]
                            && target.width == window.getWidth() && target.height == window.getHeight();
                }, 200);
                context.waitTicks(5);
                CompletableFuture<Void> capture = new CompletableFuture<>();
                context.runOnClient(client -> {
                    int width = client.getWindow().getWidth();
                    int height = client.getWindow().getHeight();
                    Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                        try (image) {
                            if (image.getWidth() != width || image.getHeight() != height) {
                                throw new AssertionError("Screenshot dimensions did not follow fullscreen transition");
                            }
                            capture.complete(null);
                        } catch (Throwable failure) {
                            capture.completeExceptionally(failure);
                        }
                    });
                });
                context.waitFor(client -> capture.isDone(), 200);
                capture.join();
            }
        } finally {
            context.runOnClient(client -> {
                client.getWindow().setFullscreen(originalFullscreen);
                client.getWindow().updateFullscreenIfChanged();
            });
            if (!originalFullscreen) {
                context.getInput().resizeWindow(960, 540);
                context.waitTicks(10);
            }
        }
    }

    private static int[] pixelSize(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var width = stack.mallocInt(1);
            var height = stack.mallocInt(1);
            if (!SDLVideo.SDL_GetWindowSizeInPixels(window, width, height)
                    || width.get(0) <= 0 || height.get(0) <= 0) {
                throw new AssertionError("SDL did not report a valid drawable size");
            }
            return new int[] {width.get(0), height.get(0)};
        }
    }
}
