package com.tiimoo.dexrefresh.core;

import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Brute-force reflective object dumper.
 *
 * <p>This is the LibreDeX methodology applied to refresh rate: rather than
 * guessing what One UI 7 calls things, walk whatever object we got handed and
 * print every field whose name or type looks display/refresh related, then
 * recurse. The real field names fall out of the log.
 *
 * <h3>Safety rules (all of these matter inside system_server)</h3>
 * <ul>
 *   <li>Never propagate an exception. Reflection on Samsung internals throws
 *       {@code NoSuchFieldException} / {@code IllegalAccessException} in places
 *       AOSP does not.</li>
 *   <li>Never call an arbitrary {@code toString()}. A server object's
 *       {@code toString()} may take locks or issue binder calls; called from a
 *       hook that already holds a DisplayManagerService lock that deadlocks the
 *       system. Only an allow-list of known-pure value types is stringified;
 *       everything else prints as {@code ClassName@hash}.</li>
 *   <li>Hard caps on lines, value length and container size, so a cyclic or
 *       enormous graph cannot wedge the caller.</li>
 * </ul>
 */
public final class Dumper {

    private Dumper() {
    }

    /** Packages worth recursing into. Everything else is a leaf. */
    private static final String[] RECURSE_PREFIXES = {
            "com.android.server.display",
            "com.android.server.wm",
            "com.android.server.power",
            "android.view",
            "android.hardware.display",
            "com.samsung.",
            "com.sec.",
    };

    /** Simple-name suffixes whose toString() is a pure field formatter. */
    private static final String[] SAFE_TO_STRING_SUFFIXES = {
            "$Mode", "Vote", "DesiredDisplayModeSpecs", "RefreshRateRange",
            "RefreshRateRanges", "DisplayInfo", "DisplayDeviceInfo", "DisplayAddress",
            "RefreshRateData", "SurfaceControl$DisplayMode",
    };

    private static final class Ctx {
        final StringBuilder sb = new StringBuilder(4096);
        final Set<Object> seen =
                java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        final int maxDepth;
        final boolean allowToString;
        int lines;
        boolean truncated;

        Ctx(int maxDepth, boolean allowToString) {
            this.maxDepth = maxDepth;
            this.allowToString = allowToString;
        }

        boolean line(String indent, String text) {
            if (lines >= Cfg.MAX_DUMP_LINES) {
                if (!truncated) {
                    truncated = true;
                    sb.append(indent).append("... [dump truncated at ")
                            .append(Cfg.MAX_DUMP_LINES).append(" lines]\n");
                }
                return false;
            }
            lines++;
            sb.append(indent).append(text).append('\n');
            return true;
        }
    }

    /**
     * Dump every interesting field reachable from {@code root}.
     *
     * @param allowToString true only when running on our own thread with no
     *                      server locks held (i.e. from a snapshot, never from
     *                      inside a hook callback).
     */
    public static String dump(String header, Object root, int maxDepth, boolean allowToString) {
        Ctx ctx = new Ctx(maxDepth, allowToString);
        try {
            ctx.line("", header + " = " + describe(root, allowToString));
            if (root != null) {
                walk(ctx, root, 1, "  ");
            }
        } catch (Throwable t) {
            ctx.sb.append("  !! dump aborted: ").append(t).append('\n');
        }
        return ctx.sb.toString();
    }

    /** Shallow single-object dump, for use inside hook callbacks. */
    public static String dumpShallow(String header, Object root) {
        return dump(header, root, Cfg.HOOK_DUMP_DEPTH, false);
    }

    private static void walk(Ctx ctx, Object obj, int depth, String indent) {
        if (obj == null || depth > ctx.maxDepth || ctx.truncated) {
            return;
        }
        if (!ctx.seen.add(obj)) {
            ctx.line(indent, "<already dumped " + shortName(obj.getClass()) + ">");
            return;
        }

        for (Class<?> k = obj.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                ctx.line(indent, "!! getDeclaredFields(" + k.getName() + ") failed: " + t);
                break;
            }
            for (Field f : fields) {
                if (f.isSynthetic()) {
                    continue;
                }
                if (!isInteresting(f)) {
                    continue;
                }
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(Modifier.isStatic(f.getModifiers()) ? null : obj);
                } catch (Throwable t) {
                    ctx.line(indent, f.getName() + " <unreadable: "
                            + t.getClass().getSimpleName() + ">");
                    continue;
                }
                String owner = k == obj.getClass() ? "" : "(" + Reflect.simple(k) + ")";
                if (!ctx.line(indent, owner + f.getName() + " : "
                        + Reflect.simple(f.getType()) + " = "
                        + describe(value, ctx.allowToString))) {
                    return;
                }
                expand(ctx, value, depth, indent + "  ");
            }
        }
    }

    /** Recurse into containers and into objects from packages we care about. */
    private static void expand(Ctx ctx, Object value, int depth, String indent) {
        if (value == null || ctx.truncated || depth >= ctx.maxDepth) {
            return;
        }
        try {
            if (value instanceof SparseArray) {
                SparseArray<?> sa = (SparseArray<?>) value;
                int n = Math.min(sa.size(), Cfg.MAX_CONTAINER_ITEMS);
                for (int i = 0; i < n; i++) {
                    Object v = sa.valueAt(i);
                    if (!ctx.line(indent, "[" + sa.keyAt(i) + "] = "
                            + describe(v, ctx.allowToString))) {
                        return;
                    }
                    expand(ctx, v, depth + 1, indent + "  ");
                }
                return;
            }
            if (value instanceof Map) {
                int i = 0;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    if (i++ >= Cfg.MAX_CONTAINER_ITEMS) {
                        ctx.line(indent, "... more entries");
                        break;
                    }
                    Object v = e.getValue();
                    if (!ctx.line(indent, "[" + describe(e.getKey(), ctx.allowToString) + "] = "
                            + describe(v, ctx.allowToString))) {
                        return;
                    }
                    expand(ctx, v, depth + 1, indent + "  ");
                }
                return;
            }
            if (value instanceof Collection) {
                int i = 0;
                for (Object v : (Collection<?>) value) {
                    if (i >= Cfg.MAX_CONTAINER_ITEMS) {
                        ctx.line(indent, "... more elements");
                        break;
                    }
                    if (!ctx.line(indent, "[" + (i++) + "] = "
                            + describe(v, ctx.allowToString))) {
                        return;
                    }
                    expand(ctx, v, depth + 1, indent + "  ");
                }
                return;
            }
            Class<?> c = value.getClass();
            if (c.isArray() && !c.getComponentType().isPrimitive()) {
                int n = Math.min(Array.getLength(value), Cfg.MAX_CONTAINER_ITEMS);
                for (int i = 0; i < n; i++) {
                    Object v = Array.get(value, i);
                    if (!ctx.line(indent, "[" + i + "] = " + describe(v, ctx.allowToString))) {
                        return;
                    }
                    expand(ctx, v, depth + 1, indent + "  ");
                }
                return;
            }
            if (shouldRecurse(c)) {
                walk(ctx, value, depth + 1, indent);
            }
        } catch (Throwable t) {
            ctx.line(indent, "!! expand failed: " + t);
        }
    }

    private static boolean isInteresting(Field f) {
        if (Cfg.INTERESTING.matcher(f.getName()).find()) {
            return true;
        }
        return Cfg.INTERESTING.matcher(Reflect.simple(f.getType())).find();
    }

    private static boolean shouldRecurse(Class<?> c) {
        String n = c.getName();
        for (String p : RECURSE_PREFIXES) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Render a value without ever risking a lock or an exception.
     *
     * @param allowToString when false, only a hard allow-list of pure value
     *                      types is stringified.
     */
    public static String describe(Object o, boolean allowToString) {
        try {
            if (o == null) {
                return "null";
            }
            Class<?> c = o.getClass();
            if (o instanceof String) {
                return '"' + clip((String) o) + '"';
            }
            if (o instanceof Number || o instanceof Boolean || o instanceof Character
                    || o instanceof Enum) {
                return String.valueOf(o);
            }
            if (c.isArray()) {
                return describeArray(o, allowToString);
            }
            if (o instanceof SparseIntArray) {
                SparseIntArray a = (SparseIntArray) o;
                StringBuilder sb = new StringBuilder("SparseIntArray{");
                for (int i = 0; i < Math.min(a.size(), Cfg.MAX_CONTAINER_ITEMS); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(a.keyAt(i)).append('=').append(a.valueAt(i));
                }
                return clip(sb.append('}').toString());
            }
            if (o instanceof SparseBooleanArray) {
                SparseBooleanArray a = (SparseBooleanArray) o;
                StringBuilder sb = new StringBuilder("SparseBooleanArray{");
                for (int i = 0; i < Math.min(a.size(), Cfg.MAX_CONTAINER_ITEMS); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(a.keyAt(i)).append('=').append(a.valueAt(i));
                }
                return clip(sb.append('}').toString());
            }
            if (o instanceof SparseArray) {
                return "SparseArray[size=" + ((SparseArray<?>) o).size() + "]";
            }
            if (o instanceof Collection) {
                return shortName(c) + "[size=" + ((Collection<?>) o).size() + "]";
            }
            if (o instanceof Map) {
                return shortName(c) + "[size=" + ((Map<?, ?>) o).size() + "]";
            }
            if (allowToString || isSafeToString(c)) {
                try {
                    return clip(shortName(c) + " " + o);
                } catch (Throwable t) {
                    return shortName(c) + " <toString threw " + t + ">";
                }
            }
            // Deliberately no identity hash. Rendering an opaque object as
            // ClassName@1f2e3d made every call look different from the last,
            // so change-triggered logging fired on every single invocation -
            // the log was full of lines like
            //   RET ...requestDisplayStateLocked -> LocalDisplayDevice$1@a582e35
            //       [was LocalDisplayDevice$1@a587913]
            // which is pure noise: the hash was never information we used, and
            // it defeated the deduplication that keeps the log readable.
            return shortName(c);
        } catch (Throwable t) {
            return "<describe failed: " + t + ">";
        }
    }

    private static String describeArray(Object arr, boolean allowToString) {
        Class<?> comp = arr.getClass().getComponentType();
        int len = Array.getLength(arr);
        StringBuilder sb = new StringBuilder(Reflect.simple(comp)).append('[').append(len).append("]{");
        int n = Math.min(len, Cfg.MAX_CONTAINER_ITEMS);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object v = Array.get(arr, i);
            sb.append(comp.isPrimitive() ? String.valueOf(v) : describe(v, allowToString));
        }
        if (n < len) {
            sb.append(", ...");
        }
        return clip(sb.append('}').toString());
    }

    private static boolean isSafeToString(Class<?> c) {
        String n = c.getName();
        if (n.startsWith("java.") || n.startsWith("android.util.")
                || n.startsWith("android.graphics.")) {
            return true;
        }
        for (String suffix : SAFE_TO_STRING_SUFFIXES) {
            if (n.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public static String shortName(Class<?> c) {
        String n = c.getName();
        if (n.startsWith("com.android.server.")) {
            return n.substring("com.android.server.".length());
        }
        if (n.startsWith("android.")) {
            return n.substring("android.".length());
        }
        return n;
    }

    private static String clip(String s) {
        if (s.length() <= Cfg.MAX_VALUE_CHARS) {
            return s;
        }
        return s.substring(0, Cfg.MAX_VALUE_CHARS) + "...<" + s.length() + " chars>";
    }

    /** Format a float as a stable, comparable refresh-rate string. */
    public static String hz(Object value) {
        if (!(value instanceof Number)) {
            return String.valueOf(value);
        }
        return String.format(Locale.US, "%.3fHz", ((Number) value).doubleValue());
    }
}
