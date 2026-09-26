package com.qb20nh.cbbg.gametest;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class IrisFixture {
  private IrisFixture() {}

  public static void installPack() {
    Path shaders = ((Path) iris("getShaderpacksDirectory")).resolve("cbbg-parity/shaders");
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

  public static Object iris(String method) {
    try {
      Class<?> iris;
      try {
        iris = Class.forName("net.irisshaders.iris.Iris");
      } catch (ClassNotFoundException olderPackage) {
        iris = Class.forName("net.coderbot.iris.Iris");
      }
      return iris.getMethod(method).invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Iris call failed: " + method, cause(failure));
    }
  }

  public static void saveAndReload() {
    Object config = iris("getIrisConfig");
    try {
      config.getClass().getMethod("save").invoke(config);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not save isolated Iris settings", cause(failure));
    }
    iris("reload");
  }

  public static void invoke(Object target, String method, Class<?> type, Object value) {
    try {
      target.getClass().getMethod(method, type).invoke(target, value);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Iris config call failed: " + method, cause(failure));
    }
  }

  static Throwable cause(ReflectiveOperationException failure) {
    return failure instanceof InvocationTargetException ? failure.getCause() : failure;
  }
}
