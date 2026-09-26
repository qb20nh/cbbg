package com.qb20nh.cbbg.gametest;

import com.mojang.blaze3d.platform.NativeImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class IrisFixture {
  private IrisFixture() {}

  public static void assertFinalShader(NativeImage image, String failureMessage) {
    int magenta = 0;
    int total = 0;
    for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
      for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
        total++;
        if ((image.getPixel(x, y) & 0xffffff) == 0xff00ff) magenta++;
      }
    }
    if (magenta < total * 0.9) throw new AssertionError(failureMessage);
  }

  public static void installPack() {
    Path shaders =
        ((Path) Objects.requireNonNull(iris("getShaderpacksDirectory")))
            .resolve("cbbg-parity/shaders");
    try {
      Files.createDirectories(shaders);
      for (String name : new String[] {"final.vsh", "final.fsh"}) {
        try (java.io.InputStream source =
            IrisFixture.class.getResourceAsStream("/cbbg-iris-fixture/" + name)) {
          if (source == null) {
            throw new AssertionError("Missing shader fixture: " + name);
          }
          Files.copy(source, shaders.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } catch (java.io.IOException failure) {
      throw new AssertionError("Could not install the isolated shader fixture", failure);
    }
  }

  public static @Nullable Object iris(String method) {
    try {
      Class<?> iris;
      try {
        iris = Class.forName("net.irisshaders.iris.Iris");
      } catch (ClassNotFoundException olderPackage) {
        iris = Class.forName("net.coderbot.iris.Iris");
      }
      return iris.getMethod(method).invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Iris call failed: " + method, cause(failure));
    }
  }

  public static void saveAndReload() {
    Object config = Objects.requireNonNull(iris("getIrisConfig"));
    try {
      config.getClass().getMethod("save").invoke(config);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Could not save isolated Iris settings", cause(failure));
    }
    iris("reload");
  }

  public static void invoke(Object target, String method, Class<?> type, Object value) {
    try {
      target.getClass().getMethod(method, type).invoke(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Iris config call failed: " + method, cause(failure));
    }
  }

  static @Nullable Throwable cause(ReflectiveOperationException failure) {
    return failure instanceof InvocationTargetException ? failure.getCause() : failure;
  }
}
