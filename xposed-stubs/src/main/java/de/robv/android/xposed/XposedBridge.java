package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/** Compile-only stub. Real implementation is provided by LSPosed at runtime. */
public final class XposedBridge {

    public static final ClassLoader BOOTCLASSLOADER = null;

    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(Throwable t) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(
            Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException,
            IllegalArgumentException, java.lang.reflect.InvocationTargetException {
        throw new UnsupportedOperationException("stub");
    }
}
