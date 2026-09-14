package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-only stub. Real implementation is provided by LSPosed at runtime. */
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** All members below are declared on MethodHookParam in the real API too. */
    public static final class MethodHookParam {

        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            throw new UnsupportedOperationException("stub");
        }

        public void setResult(Object result) {
            throw new UnsupportedOperationException("stub");
        }

        public Throwable getThrowable() {
            throw new UnsupportedOperationException("stub");
        }

        public boolean hasThrowable() {
            throw new UnsupportedOperationException("stub");
        }

        public void setThrowable(Throwable throwable) {
            throw new UnsupportedOperationException("stub");
        }

        public Object getResultOrThrowable() throws Throwable {
            throw new UnsupportedOperationException("stub");
        }
    }

    public class Unhook {

        public Member getHookedMethod() {
            throw new UnsupportedOperationException("stub");
        }

        public void unhook() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
