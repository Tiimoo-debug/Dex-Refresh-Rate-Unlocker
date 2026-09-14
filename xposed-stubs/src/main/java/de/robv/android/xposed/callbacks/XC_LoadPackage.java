package de.robv.android.xposed.callbacks;

/** Compile-only stub. Real implementation is provided by LSPosed at runtime. */
public abstract class XC_LoadPackage {

    /** All fields below are declared on LoadPackageParam in the real API too. */
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
