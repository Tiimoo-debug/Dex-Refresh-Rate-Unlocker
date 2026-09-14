package com.tiimoo.dexrefresh.hooks;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.Dumper;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;
import com.tiimoo.dexrefresh.core.Throttle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Generic "hook it and tell me what went through" machinery.
 *
 * <p>Every hook installed by this class is strictly observational: it never
 * calls {@code setResult}, never writes a field, never changes an argument.
 * That invariant is what makes this first pass safe to leave running on a daily
 * driver.
 *
 * <p>Logging is change-triggered rather than call-triggered. Display code paths
 * run at frame rate; printing every call would bury the signal and hammer the
 * log. Printing only transitions produces exactly the before/after diff we want
 * when DeX starts.
 */
public final class HookEngine {

    private HookEngine() {
    }

    /** Optional extra work for a specific site, still observation-only. */
    public interface OnCall {
        void onCall(String site, XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    /** Arg/result types worth a nested field dump when they change. */
    private static final Pattern EXPAND_TYPES = Pattern.compile(
            "(?i)vote|modespecs|refreshrate|displayinfo|devicinfo|deviceinfo|\\$Mode$|modes$");

    private static final ConcurrentHashMap<String, Boolean> ERRORS_REPORTED = new ConcurrentHashMap<>();

    /**
     * Hook every declared method of {@code className} matching {@code pattern}.
     *
     * @return number of methods successfully hooked (0 if the class is absent)
     */
    public static int hookMatching(ClassLoader cl, String className, Pattern pattern,
                                   boolean logResult) {
        Class<?> c = Reflect.cls(cl, className);
        if (c == null) {
            ProbeLog.postNow("class ABSENT  " + className);
            return 0;
        }
        List<Method> methods = Reflect.methodsMatching(c, pattern);
        if (methods.isEmpty()) {
            ProbeLog.postNow("class present, no matching methods: " + className);
            return 0;
        }
        int n = 0;
        for (Method m : methods) {
            if (Cfg.NEVER_HOOK.matcher(m.getName()).find()) {
                ProbeLog.postNow("  skipped (hot path) " + Dumper.shortName(c)
                        + "." + Reflect.sig(m));
                continue;
            }
            if (hook(m, Dumper.shortName(c) + "#" + m.getName(), logResult, null) != null) {
                ProbeLog.postNow("  hooked " + Dumper.shortName(c) + "." + Reflect.sig(m));
                n++;
            }
        }
        return n;
    }

    /** Hook every overload with the given name. */
    public static int hookNamed(ClassLoader cl, String className, String methodName,
                                boolean logResult, OnCall extra) {
        Class<?> c = Reflect.cls(cl, className);
        if (c == null) {
            ProbeLog.postNow("class ABSENT  " + className);
            return 0;
        }
        List<Method> methods = Reflect.methodsNamed(c, methodName);
        if (methods.isEmpty()) {
            ProbeLog.postNow("method ABSENT " + className + "." + methodName);
            return 0;
        }
        int n = 0;
        for (Method m : methods) {
            if (hook(m, Dumper.shortName(c) + "#" + m.getName(), logResult, extra) != null) {
                ProbeLog.postNow("  hooked " + Dumper.shortName(c) + "." + Reflect.sig(m));
                n++;
            }
        }
        return n;
    }

    /** Hook every constructor of a class. */
    public static int hookConstructors(ClassLoader cl, String className, OnCall extra) {
        Class<?> c = Reflect.cls(cl, className);
        if (c == null) {
            ProbeLog.postNow("class ABSENT  " + className);
            return 0;
        }
        int n = 0;
        for (Constructor<?> ctor : Reflect.constructors(c)) {
            if (hook(ctor, Dumper.shortName(c) + "#<init>", false, extra) != null) {
                ProbeLog.postNow("  hooked " + Dumper.shortName(c) + "." + Reflect.sig(ctor));
                n++;
            }
        }
        return n;
    }

    /** Install one observational hook. Returns null if hooking failed. */
    public static Object hook(Member member, String site, boolean logResult, OnCall extra) {
        try {
            return XposedBridge.hookMethod(member, logging(site, logResult, extra));
        } catch (Throwable t) {
            ProbeLog.postNow("hook FAILED " + site + ": " + t);
            return null;
        }
    }

    private static XC_MethodHook logging(final String site, final boolean logResult,
                                         final OnCall extra) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    logCall(site, param);
                } catch (Throwable t) {
                    reportOnce(site + ":before", t);
                }
                // Extra work runs even when the call itself was deduplicated,
                // because state capture must not depend on log throttling.
                if (extra != null) {
                    try {
                        extra.onCall(site, param);
                    } catch (Throwable t) {
                        reportOnce(site + ":extra", t);
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!logResult) {
                    return;
                }
                try {
                    logResult(site, param);
                } catch (Throwable t) {
                    reportOnce(site + ":after", t);
                }
            }
        };
    }

    private static void logCall(String site, XC_MethodHook.MethodHookParam param) {
        String key = "call:" + site;
        String args = renderArgs(param.args);
        String prev = Throttle.previous(key);
        if (!Throttle.changed(key, args)) {
            return;
        }
        if (!Throttle.allow(key)) {
            return;
        }
        long suppressed = Throttle.takeSuppressed(key);
        ProbeLog.post("CALL %s(%s)%s%s",
                site,
                args,
                prev == null ? "" : "   [was " + prev + "]",
                suppressed > 0 ? "   [+" + suppressed + " suppressed]" : "");
        expandArgs(site, param.args);
    }

    private static void logResult(String site, XC_MethodHook.MethodHookParam param) {
        Object result;
        try {
            result = param.getResult();
        } catch (Throwable t) {
            return;
        }
        if (result == null) {
            return;
        }
        String key = "ret:" + site;
        String rendered = Dumper.describe(result, false);
        String prev = Throttle.previous(key);
        if (!Throttle.changed(key, rendered) || !Throttle.allow(key)) {
            return;
        }
        ProbeLog.post("RET  %s -> %s%s", site, rendered,
                prev == null ? "" : "   [was " + prev + "]");
        if (shouldExpand(result)) {
            ProbeLog.postBlock(Dumper.dumpShallow("     result", result));
        }
    }

    private static void expandArgs(String site, Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (shouldExpand(args[i])) {
                ProbeLog.postBlock(Dumper.dumpShallow("     arg" + i, args[i]));
            }
        }
    }

    private static boolean shouldExpand(Object o) {
        if (o == null || o instanceof String || o instanceof Number
                || o instanceof Boolean || o instanceof Character) {
            return false;
        }
        return EXPAND_TYPES.matcher(o.getClass().getName()).find();
    }

    private static String renderArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(Dumper.describe(args[i], false));
        }
        return sb.toString();
    }

    /**
     * Report a hook-internal failure once per site. A broken probe must stay
     * quiet rather than spam the log of a device that is otherwise working.
     */
    static void reportOnce(String key, Throwable t) {
        if (ERRORS_REPORTED.putIfAbsent(key, Boolean.TRUE) == null) {
            ProbeLog.post("!! probe error at %s: %s: %s (further occurrences silenced)",
                    key, t.getClass().getName(), t.getMessage());
        }
    }

    /** Log budget helper shared with the hand-written hooks. */
    static boolean shouldLog(String key, String value) {
        return Throttle.changed(key, value) && Throttle.allow(key,
                Cfg.RATE_LIMIT_PER_WINDOW, Cfg.RATE_LIMIT_WINDOW_MS);
    }
}
