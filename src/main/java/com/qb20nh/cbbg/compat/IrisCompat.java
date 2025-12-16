package com.qb20nh.cbbg.compat;

import com.qb20nh.cbbg.CbbgClient;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

public final class IrisCompat {

    private static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
    private static volatile boolean loggedReflectionFailure = false;

    private IrisCompat() {}

    /**
     * @return true if Iris is loaded and a shaderpack is currently in use.
     */
    public static boolean isShaderPackActive() {
        if (!IRIS_LOADED) {
            return false;
        }

        try {
            // Iris public API (no compile-time dependency):
            // net.irisshaders.iris.api.v0.IrisApi#getInstance().isShaderPackInUse()
            final Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            final Object irisApi = invokeStaticNoArgs(irisApiClass, "getInstance");
            if (irisApi != null) {
                final Boolean result = invokeBooleanNoArgs(irisApi, "isShaderPackInUse");
                if (result != null) {
                    return result;
                }
            }
        } catch (Exception e) {
            logReflectionFailureOnce(e);
            // If Iris is present but the API contract changed, be conservative and disable
            // cbbg.
            return true;
        }

        // Iris loaded, but we couldn't determine state via the known API.
        // Be conservative to avoid breaking shader pipelines.
        logReflectionFailureOnce(null);
        return true;
    }

    private static Object invokeStaticNoArgs(Class<?> clazz, String methodName) {
        try {
            final Method m = clazz.getMethod(methodName);
            return m.invoke(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Boolean invokeBooleanNoArgs(Object instance, String methodName) {
        try {
            final Method m = instance.getClass().getMethod(methodName);
            final Object result = m.invoke(instance);
            return (result instanceof Boolean b) ? b : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void logReflectionFailureOnce(Throwable t) {
        if (loggedReflectionFailure) {
            return;
        }
        loggedReflectionFailure = true;

        if (t == null) {
            CbbgClient.LOGGER.warn(
                    "Iris detected but shaderpack state could not be determined (unknown API). Disabling cbbg for safety.");
            return;
        }

        CbbgClient.LOGGER.warn(
                "Failed to query Iris shaderpack state via reflection; disabling cbbg for safety.",
                t);
    }
}
