package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "what does this firmware actually have?".
 *
 * <p>system_server's classes come from the boot classpath, which cannot be
 * enumerated at runtime, so there are two strategies here and both are needed:
 *
 * <ol>
 *   <li>{@link #logPresenceReport} probes a curated list of names. Cheap, but it
 *       only finds names we thought of.</li>
 *   <li>{@link #graphScan} walks the live DisplayManagerService object graph and
 *       reports every Samsung/display class it reaches. That one finds the names
 *       we could not have guessed, which on a One UI device is most of them.</li>
 * </ol>
 */
public final class ClassScout {

    private ClassScout() {
    }

    /**
     * Names worth checking. AOSP names are near-certain; Samsung-prefixed ones
     * are guesses, listed so the log tells us definitively whether they exist
     * rather than leaving it an open question.
     */
    private static final String[] CANDIDATES = {
            // --- AOSP display core ---
            "com.android.server.display.DisplayManagerService",
            "com.android.server.display.DisplayManagerService$BinderService",
            "com.android.server.display.DisplayManagerService$LocalService",
            "com.android.server.display.LogicalDisplay",
            "com.android.server.display.LogicalDisplayMapper",
            "com.android.server.display.DisplayDevice",
            "com.android.server.display.DisplayDeviceInfo",
            "com.android.server.display.DisplayDeviceConfig",
            "com.android.server.display.LocalDisplayAdapter",
            "com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice",
            "com.android.server.display.VirtualDisplayAdapter",
            "com.android.server.display.DisplayPowerController",
            "com.android.server.display.DisplayPowerController2",
            // --- mode selection: Android 14 layout ---
            "com.android.server.display.mode.DisplayModeDirector",
            "com.android.server.display.mode.DisplayModeDirector$DesiredDisplayModeSpecs",
            "com.android.server.display.mode.Vote",
            "com.android.server.display.mode.VotesStorage",
            "com.android.server.display.mode.SkinThermalStatusObserver",
            // --- mode selection: pre-Android-14 layout ---
            "com.android.server.display.DisplayModeDirector",
            "com.android.server.display.DisplayModeDirector$Vote",
            "com.android.server.display.DisplayModeDirector$DesiredDisplayModeSpecs",
            // --- framework surfaces ---
            "android.view.Display$Mode",
            "android.view.DisplayInfo",
            "android.view.SurfaceControl",
            "android.view.SurfaceControl$DesiredDisplayModeSpecs",
            "android.view.SurfaceControl$RefreshRateRange",
            "android.view.SurfaceControl$RefreshRateRanges",
            "android.view.SurfaceControl$DisplayMode",
            "android.hardware.display.DisplayManagerInternal",
            "android.hardware.display.DisplayManagerGlobal",
            "android.hardware.display.IDisplayManager",
            // --- Samsung / DeX guesses, unverified on this device ---
            "com.samsung.android.desktopmode.SemDesktopModeManager",
            "com.samsung.android.desktopmode.SemDesktopModeState",
            "com.android.server.desktopmode.DesktopModeService",
            "com.android.server.desktopmode.DesktopModeManagerService",
            "com.android.server.display.SemDisplayModeDirector",
            "com.android.server.display.SemLocalDisplayAdapter",
            "com.android.server.display.RefreshRateController",
            "com.android.server.display.SemRefreshRateController",
            "com.android.server.display.mode.SemVote",
    };

    public static void logPresenceReport(ClassLoader cl) {
        ProbeLog.postNow("---- class presence report ----");
        List<String> absent = new ArrayList<>();
        for (String name : CANDIDATES) {
            if (Reflect.cls(cl, name) != null) {
                ProbeLog.postNow("  PRESENT " + name);
            } else {
                absent.add(name);
            }
        }
        for (String name : absent) {
            ProbeLog.postNow("  absent  " + name);
        }
        ProbeLog.postNow("---- end class presence report ----");
    }

    public static void logInnerClasses(ClassLoader cl, String className) {
        Class<?> c = Reflect.cls(cl, className);
        if (c == null) {
            ProbeLog.postNow("inner-class scan: " + className + " absent");
            return;
        }
        try {
            ProbeLog.postNow("inner classes of " + className + ":");
            for (Class<?> inner : c.getDeclaredClasses()) {
                ProbeLog.postNow("   " + inner.getName());
            }
        } catch (Throwable t) {
            ProbeLog.postNow("inner-class scan failed: " + t);
        }
    }

    /** Full field + method inventory of one class. */
    public static String dumpClassSignature(ClassLoader cl, String className) {
        Class<?> c = Reflect.cls(cl, className);
        if (c == null) {
            return "class absent: " + className + "\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== signature of ").append(c.getName()).append(" ===\n");
        Class<?> sup = c.getSuperclass();
        sb.append("extends ").append(sup == null ? "-" : sup.getName()).append('\n');
        try {
            for (Class<?> i : c.getInterfaces()) {
                sb.append("implements ").append(i.getName()).append('\n');
            }
        } catch (Throwable ignored) {
            // interfaces unavailable; not important
        }
        try {
            sb.append("-- fields --\n");
            Field[] fields = c.getDeclaredFields();
            List<String> lines = new ArrayList<>();
            for (Field f : fields) {
                lines.add(String.format("   %s%s %s : %s",
                        Modifier.isStatic(f.getModifiers()) ? "static " : "",
                        Modifier.isFinal(f.getModifiers()) ? "final" : "",
                        f.getName(), f.getType().getName()));
            }
            Collections.sort(lines);
            for (String l : lines) {
                sb.append(l).append('\n');
            }
        } catch (Throwable t) {
            sb.append("   !! fields unavailable: ").append(t).append('\n');
        }
        try {
            sb.append("-- methods --\n");
            List<String> lines = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic()) {
                    continue;
                }
                lines.add("   " + Reflect.sig(m));
            }
            Collections.sort(lines);
            for (String l : lines) {
                sb.append(l).append('\n');
            }
        } catch (Throwable t) {
            sb.append("   !! methods unavailable: ").append(t).append('\n');
        }
        sb.append("=== end signature ===\n");
        return sb.toString();
    }

    /**
     * Walk the live object graph and report every display-related class reached,
     * with the field path that got there.
     *
     * <p>This is how we learn One UI 7's real class names without guessing:
     * whatever Samsung inserted into the display pipeline is reachable from
     * DisplayManagerService, whatever they called it.
     */
    public static String graphScan(Object root, int maxDepth) {
        Map<String, String> found = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        try {
            visit(root, "DMS", 0, maxDepth, found, seen);
        } catch (Throwable t) {
            return "graph scan failed: " + t + "\n";
        }
        StringBuilder sb = new StringBuilder("=== graph scan (")
                .append(found.size()).append(" classes) ===\n");
        List<String> names = new ArrayList<>(found.keySet());
        Collections.sort(names);
        for (String n : names) {
            sb.append("   ").append(n).append("\n        via ").append(found.get(n)).append('\n');
        }
        sb.append("=== end graph scan ===\n");
        return sb.toString();
    }

    private static void visit(Object obj, String path, int depth, int maxDepth,
                              Map<String, String> found, Set<Object> seen) {
        if (obj == null || depth > maxDepth || found.size() > 400 || !seen.add(obj)) {
            return;
        }
        Class<?> c = obj.getClass();
        String name = c.getName();
        if (isDisplayRelated(name) && !found.containsKey(name)) {
            found.put(name, path);
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                return;
            }
            for (Field f : fields) {
                if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> type = f.getType();
                if (type.isPrimitive()) {
                    continue;
                }
                if (!isDisplayRelated(type.getName())
                        && !Cfg.INTERESTING.matcher(f.getName()).find()
                        && !java.util.Collection.class.isAssignableFrom(type)
                        && !android.util.SparseArray.class.isAssignableFrom(type)) {
                    continue;
                }
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(obj);
                } catch (Throwable t) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                String childPath = path + "." + f.getName();
                if (value instanceof android.util.SparseArray) {
                    android.util.SparseArray<?> sa = (android.util.SparseArray<?>) value;
                    for (int i = 0; i < Math.min(sa.size(), 8); i++) {
                        visit(sa.valueAt(i), childPath + "[" + sa.keyAt(i) + "]",
                                depth + 1, maxDepth, found, seen);
                    }
                } else if (value instanceof java.util.Collection) {
                    int i = 0;
                    for (Object v : (java.util.Collection<?>) value) {
                        if (i >= 8) {
                            break;
                        }
                        visit(v, childPath + "[" + (i++) + "]", depth + 1, maxDepth, found, seen);
                    }
                } else if (value.getClass().isArray()
                        && !value.getClass().getComponentType().isPrimitive()) {
                    int len = Math.min(java.lang.reflect.Array.getLength(value), 8);
                    for (int i = 0; i < len; i++) {
                        visit(java.lang.reflect.Array.get(value, i), childPath + "[" + i + "]",
                                depth + 1, maxDepth, found, seen);
                    }
                } else {
                    visit(value, childPath, depth + 1, maxDepth, found, seen);
                }
            }
        }
    }

    private static boolean isDisplayRelated(String className) {
        if (className.startsWith("com.android.server.display")
                || className.startsWith("com.samsung.")
                || className.startsWith("com.sec.")
                || className.startsWith("android.hardware.display")) {
            return true;
        }
        if (className.startsWith("android.view.")) {
            return Cfg.INTERESTING.matcher(
                    className.substring("android.view.".length())).find();
        }
        return false;
    }
}
