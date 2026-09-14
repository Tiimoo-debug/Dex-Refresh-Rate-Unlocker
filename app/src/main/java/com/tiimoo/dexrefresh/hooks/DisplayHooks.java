package com.tiimoo.dexrefresh.hooks;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.Dumper;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;
import com.tiimoo.dexrefresh.core.Throttle;
import com.tiimoo.dexrefresh.probe.ClassScout;
import com.tiimoo.dexrefresh.probe.ControlReceiver;
import com.tiimoo.dexrefresh.probe.ProbeState;
import com.tiimoo.dexrefresh.probe.Snapshots;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Installs the observation hooks inside system_server.
 *
 * <p>Class names are tried in order and every one is optional, because the two
 * things we know for certain are that One UI 7 is not AOSP and that it is not
 * the Android 16 firmware LibreDeX was written against. Anything that does not
 * resolve is logged as ABSENT and the probe carries on.
 */
public final class DisplayHooks {

    private DisplayHooks() {
    }

    private static final String DMS = "com.android.server.display.DisplayManagerService";
    private static final String DMS_BINDER = DMS + "$BinderService";
    private static final String DMS_LOCAL = DMS + "$LocalService";

    /** DisplayModeDirector moved to the .mode subpackage in Android 14. */
    private static final String[] DMD_NAMES = {
            "com.android.server.display.mode.DisplayModeDirector",
            "com.android.server.display.DisplayModeDirector",
    };

    private static final String[] VOTE_NAMES = {
            "com.android.server.display.mode.Vote",
            "com.android.server.display.DisplayModeDirector$Vote",
    };

    private static final String[] VOTES_STORAGE_NAMES = {
            "com.android.server.display.mode.VotesStorage",
            "com.android.server.display.VotesStorage",
    };

    private static final String LOCAL_DISPLAY_DEVICE =
            "com.android.server.display.LocalDisplayAdapter$LocalDisplayDevice";
    private static final String LOGICAL_DISPLAY_MAPPER =
            "com.android.server.display.LogicalDisplayMapper";
    private static final String SURFACE_CONTROL = "android.view.SurfaceControl";
    private static final String DISPLAY_MODE = "android.view.Display$Mode";

    public static void install(ClassLoader cl) {
        ProbeState.systemServerClassLoader = cl;

        ProbeLog.postNow("==== installing system_server probes ====");
        harvestVotePriorities(cl);
        captureServiceInstances(cl);
        hookBootPhase(cl);
        hookVotes(cl);
        hookModeDirector(cl);
        hookLocalDisplayDevice(cl);
        hookSurfaceControl(cl);
        hookDisplayModeConstruction(cl);
        hookDisplayLifecycle(cl);
        hookAssortedByName(cl);
        ClassScout.logPresenceReport(cl);
        ProbeLog.postNow("==== system_server probes installed ====");
    }

    // ------------------------------------------------------------------
    // Vote priority table
    // ------------------------------------------------------------------

    /**
     * Read the {@code PRIORITY_*} constants off this firmware's Vote class.
     *
     * <p>This is the single most useful thing the probe prints at boot. The
     * numbers differ between Android versions and Samsung adds its own, so the
     * priority values LibreDeX hardcoded for One UI 8 / Android 16 mean nothing
     * here. Whatever this table says is the truth for this device.
     */
    private static void harvestVotePriorities(ClassLoader cl) {
        Class<?> vote = Reflect.firstClass(cl, VOTE_NAMES);
        if (vote == null) {
            ProbeLog.postNow("Vote class NOT FOUND - tried " + String.join(", ", VOTE_NAMES));
            return;
        }
        ProbeLog.postNow("Vote class = " + vote.getName());
        collectPriorityConstants(vote);
        for (String dmdName : DMD_NAMES) {
            Class<?> dmd = Reflect.cls(cl, dmdName);
            if (dmd != null) {
                collectPriorityConstants(dmd);
            }
        }
        List<Integer> keys = new ArrayList<>(ProbeState.VOTE_PRIORITY_NAMES.keySet());
        Collections.sort(keys);
        ProbeLog.postNow("---- vote priority table (" + keys.size() + " entries) ----");
        for (Integer k : keys) {
            ProbeLog.postNow(String.format(Locale.US, "   %3d = %s",
                    k, ProbeState.VOTE_PRIORITY_NAMES.get(k)));
        }
        ProbeLog.postNow("---- end vote priority table ----");
    }

    private static void collectPriorityConstants(Class<?> c) {
        Field[] fields;
        try {
            fields = c.getDeclaredFields();
        } catch (Throwable t) {
            return;
        }
        for (Field f : fields) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            if (!f.getName().startsWith("PRIORITY")) {
                continue;
            }
            try {
                f.setAccessible(true);
                ProbeState.VOTE_PRIORITY_NAMES.put(f.getInt(null), f.getName());
            } catch (Throwable ignored) {
                // constant unreadable; the numeric value still shows in the log
            }
        }
    }

    // ------------------------------------------------------------------
    // Instance capture
    // ------------------------------------------------------------------

    private static void captureServiceInstances(ClassLoader cl) {
        captureCtor(cl, DMS, "DisplayManagerService", new Capture() {
            @Override
            public void accept(Object instance) {
                ProbeState.displayManagerService = instance;
                ProbeState.systemContext = Reflect.get(instance, "mContext");
            }
        });
        for (String dmd : DMD_NAMES) {
            if (Reflect.cls(cl, dmd) != null) {
                captureCtor(cl, dmd, "DisplayModeDirector", new Capture() {
                    @Override
                    public void accept(Object instance) {
                        ProbeState.displayModeDirector = instance;
                    }
                });
                break;
            }
        }
        captureCtor(cl, LOGICAL_DISPLAY_MAPPER, "LogicalDisplayMapper", new Capture() {
            @Override
            public void accept(Object instance) {
                ProbeState.logicalDisplayMapper = instance;
            }
        });
    }

    private interface Capture {
        void accept(Object instance);
    }

    /** Grab the instance as each constructor returns. */
    private static void captureCtor(ClassLoader cl, String className, final String label,
                                    final Capture capture) {
        Class<?> c = Reflect.cls(cl, className);
        if (c == null) {
            ProbeLog.postNow("capture SKIPPED, class absent: " + className);
            return;
        }
        try {
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        capture.accept(param.thisObject);
                        ProbeLog.post("captured %s instance %s", label,
                                Dumper.describe(param.thisObject, false));
                    } catch (Throwable t) {
                        HookEngine.reportOnce("capture:" + label, t);
                    }
                }
            });
            ProbeLog.postNow("capturing " + label + " via " + className + ".<init>");
        } catch (Throwable t) {
            ProbeLog.postNow("capture hook failed for " + className + ": " + t);
        }
    }

    // ------------------------------------------------------------------
    // Boot phase -> register the control receiver, take a baseline
    // ------------------------------------------------------------------

    private static void hookBootPhase(ClassLoader cl) {
        Class<?> c = Reflect.cls(cl, DMS);
        if (c == null) {
            return;
        }
        List<Method> methods = Reflect.methodsNamed(c, "onBootPhase");
        if (methods.isEmpty()) {
            ProbeLog.postNow("DisplayManagerService.onBootPhase absent");
            return;
        }
        for (Method m : methods) {
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object phase = param.args != null && param.args.length > 0
                                    ? param.args[0] : null;
                            ProbeLog.post("DMS.onBootPhase(%s)", phase);
                            // SystemService.PHASE_BOOT_COMPLETED
                            if (phase instanceof Integer && (Integer) phase >= 1000) {
                                onBootCompleted(param.thisObject);
                            }
                        } catch (Throwable t) {
                            HookEngine.reportOnce("onBootPhase", t);
                        }
                    }
                });
            } catch (Throwable t) {
                ProbeLog.postNow("onBootPhase hook failed: " + t);
            }
        }
    }

    private static volatile boolean bootHandled;

    private static synchronized void onBootCompleted(Object dms) {
        if (bootHandled) {
            return;
        }
        bootHandled = true;
        if (ProbeState.displayManagerService == null) {
            ProbeState.displayManagerService = dms;
        }
        if (ProbeState.systemContext == null) {
            ProbeState.systemContext = Reflect.get(dms, "mContext");
        }
        // Everything below touches binder / ContentResolver, so it must not run
        // on this thread: onBootPhase is called with server locks in play.
        Snapshots.runLater(0, new Runnable() {
            @Override
            public void run() {
                ControlReceiver.register(ProbeState.systemContext);
                Snapshots.take("boot-baseline", Cfg.SNAPSHOT_DEPTH, false);
            }
        });
    }

    // ------------------------------------------------------------------
    // The choke point: every refresh-rate constraint is a Vote
    // ------------------------------------------------------------------

    /**
     * Hook vote storage.
     *
     * <p>On Android 14 and 15 alike, every constraint on the allowed
     * refresh-rate range is expressed as a {@code Vote} filed under a priority,
     * and the winning range is the intersection of the votes. So whatever drops
     * the panel to 60 Hz under DeX has to show up here as a vote appearing or
     * changing. Logging only the transitions turns this into a direct answer to
     * "what filtered out the higher rates, and when".
     *
     * <p>Android 15 (this device) turned {@code Vote} from a concrete class
     * into an interface with one implementation per kind - SizeVote,
     * SupportedModesVote, RefreshRateVote$PhysicalVote and so on - so the class
     * name in the log already tells you what kind of restriction a vote is.
     */
    private static void hookVotes(ClassLoader cl) {
        Class<?> storage = Reflect.firstClass(cl, VOTES_STORAGE_NAMES);
        if (storage != null) {
            ProbeLog.postNow("VotesStorage class = " + storage.getName());
            hookVoteMethods(storage, "updateVote");
            // Verified present in AOSP 14: a second entry point that files a
            // vote against the GLOBAL pseudo-display (id -1) rather than one
            // real display. A cap that applies "to the panel" regardless of
            // which display triggered it arrives through here, so missing it
            // would mean missing the likeliest culprit entirely.
            hookVoteMethods(storage, "updateGlobalVote");
            hookVoteMethods(storage, "removeVote");
            hookVoteMethods(storage, "removeAllVotes");
            return;
        }
        ProbeLog.postNow("VotesStorage absent; falling back to DisplayModeDirector vote methods");
        for (String dmdName : DMD_NAMES) {
            Class<?> dmd = Reflect.cls(cl, dmdName);
            if (dmd == null) {
                continue;
            }
            hookVoteMethods(dmd, "updateVoteLocked");
            hookVoteMethods(dmd, "updateVote");
            hookVoteMethods(dmd, "updateGlobalVote");
            return;
        }
    }

    /** VotesStorage.GLOBAL_ID: the pseudo-display global votes are filed under. */
    private static final int GLOBAL_DISPLAY_ID = -1;

    private static void hookVoteMethods(Class<?> owner, String methodName) {
        List<Method> methods = Reflect.methodsNamed(owner, methodName);
        if (methods.isEmpty()) {
            ProbeLog.postNow("  no " + methodName + " on " + owner.getName());
            return;
        }
        for (Method m : methods) {
            Class<?>[] pt = m.getParameterTypes();
            // Two shapes exist in AOSP 14:
            //   updateVote(int displayId, int priority, Vote)
            //   updateGlobalVote(int priority, Vote)
            // Decide from the parameter types rather than the method name, so a
            // Samsung rename does not make us misread positional arguments.
            final boolean perDisplay =
                    pt.length >= 2 && pt[0] == int.class && pt[1] == int.class;
            final boolean global =
                    pt.length >= 1 && pt[0] == int.class && !perDisplay;
            if (!perDisplay && !global) {
                // Unexpected signature: fall back to the generic logger rather
                // than misreading positional arguments.
                ProbeLog.postNow("  " + methodName + Reflect.sig(m)
                        + " has an unexpected shape, using generic logging");
                HookEngine.hook(m, Dumper.shortName(owner) + "#" + methodName, false, null);
                continue;
            }
            final String label = methodName;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            logVote(label, param.args, perDisplay);
                        } catch (Throwable t) {
                            HookEngine.reportOnce("vote:" + label, t);
                        }
                    }
                });
                ProbeLog.postNow("  hooked " + Dumper.shortName(owner) + "." + Reflect.sig(m)
                        + (perDisplay ? "  [per-display]" : "  [global]"));
            } catch (Throwable t) {
                ProbeLog.postNow("  hook failed " + methodName + ": " + t);
            }
        }
    }

    private static void logVote(String action, Object[] args, boolean perDisplay) {
        if (args == null || args.length < 1) {
            return;
        }
        final int displayId;
        final int priority;
        final Object vote;
        if (perDisplay) {
            if (args.length < 2) {
                return;
            }
            displayId = args[0] instanceof Integer ? (Integer) args[0] : Integer.MIN_VALUE;
            priority = args[1] instanceof Integer ? (Integer) args[1] : Integer.MIN_VALUE;
            vote = args.length > 2 ? args[2] : null;
        } else {
            displayId = GLOBAL_DISPLAY_ID;
            priority = args[0] instanceof Integer ? (Integer) args[0] : Integer.MIN_VALUE;
            vote = args.length > 1 ? args[1] : null;
        }

        String rendered = Dumper.describe(vote, false);
        String key = "vote:" + displayId + ":" + priority;
        String prev = Throttle.previous(key);
        if (!Throttle.changed(key, rendered)) {
            return;
        }
        // Generous budget: vote transitions are exactly what we came for, but a
        // pathological flapping vote still must not flood the log.
        if (!Throttle.allow(key, 120, Cfg.RATE_LIMIT_WINDOW_MS)) {
            return;
        }
        ProbeState.record(key, rendered);
        ProbeLog.post("VOTE %s display=%d priority=%d(%s) -> %s%s",
                action, displayId, priority,
                ProbeState.votePriorityName(priority), rendered,
                prev == null ? "" : "   [was " + prev + "]");
        if (vote != null) {
            ProbeLog.postBlock(Dumper.dumpShallow("      vote", vote));
        }
    }

    // ------------------------------------------------------------------
    // Mode selection
    // ------------------------------------------------------------------

    private static void hookModeDirector(ClassLoader cl) {
        for (String name : DMD_NAMES) {
            Class<?> c = Reflect.cls(cl, name);
            if (c == null) {
                continue;
            }
            ProbeLog.postNow("DisplayModeDirector class = " + c.getName());
            HookEngine.hookNamed(cl, name, "getDesiredDisplayModeSpecs", true, null);
            HookEngine.hookMatching(cl, name, Cfg.HOOKABLE_METHOD, true);
            return;
        }
        ProbeLog.postNow("DisplayModeDirector NOT FOUND - tried " + String.join(", ", DMD_NAMES));
    }

    /**
     * The last mile: what the framework actually commits to the display device
     * (and from there to SurfaceFlinger). If the vote log looks sane but this
     * shows 60 Hz, the filtering happens below the mode director.
     */
    private static void hookLocalDisplayDevice(ClassLoader cl) {
        int n = HookEngine.hookMatching(cl, LOCAL_DISPLAY_DEVICE, Cfg.HOOKABLE_METHOD, true);
        if (n == 0) {
            // Samsung sometimes subclasses or renames the adapter; report the
            // real inner class names so we can retarget.
            ClassScout.logInnerClasses(cl, "com.android.server.display.LocalDisplayAdapter");
        }
    }

    /**
     * Samsung talks to SurfaceFlinger through extra SurfaceControl entry points
     * that do not exist in AOSP (on One UI 8 one of them is notifyHFRmode).
     * Rather than guess the One UI 7 name, hook every declared method whose name
     * matches the refresh-rate pattern and print the full inventory.
     */
    private static void hookSurfaceControl(ClassLoader cl) {
        Class<?> sc = Reflect.cls(cl, SURFACE_CONTROL);
        if (sc == null) {
            ProbeLog.postNow("android.view.SurfaceControl NOT FOUND");
            return;
        }
        ProbeLog.postNow("---- SurfaceControl methods matching the interest filter ----");
        for (Method m : Reflect.methodsMatching(sc, Cfg.INTERESTING)) {
            ProbeLog.postNow("   " + Reflect.sig(m));
        }
        ProbeLog.postNow("---- end SurfaceControl inventory ----");
        HookEngine.hookMatching(cl, SURFACE_CONTROL, Cfg.HOOKABLE_METHOD, true);
    }

    /**
     * Every Display.Mode built in system_server. This is what produces the
     * "available refresh rate modes" list, so a mode list that shrinks when DeX
     * starts shows up right here.
     */
    private static void hookDisplayModeConstruction(ClassLoader cl) {
        Class<?> c = Reflect.cls(cl, DISPLAY_MODE);
        if (c == null) {
            ProbeLog.postNow("android.view.Display$Mode NOT FOUND");
            return;
        }
        ProbeLog.postNow("---- Display.Mode constructors on this firmware ----");
        for (java.lang.reflect.Constructor<?> ctor : Reflect.constructors(c)) {
            ProbeLog.postNow("   " + Reflect.sig(ctor));
        }
        ProbeLog.postNow("---- end Display.Mode constructors ----");
        try {
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String args = describeAll(param.args);
                        // Tighter budget than elsewhere: mode construction can
                        // burst during a display transition.
                        if (Throttle.changed("modector", args)
                                && Throttle.allow("modector", 30, Cfg.RATE_LIMIT_WINDOW_MS)) {
                            ProbeLog.post("MODE new Display.Mode(%s)", args);
                        }
                    } catch (Throwable t) {
                        HookEngine.reportOnce("modector", t);
                    }
                }
            });
            ProbeLog.postNow("hooked Display.Mode constructors");
        } catch (Throwable t) {
            ProbeLog.postNow("Display.Mode constructor hook failed: " + t);
        }
    }

    /** Display add/remove brackets the DeX session; take a snapshot around it. */
    private static void hookDisplayLifecycle(ClassLoader cl) {
        Class<?> c = Reflect.cls(cl, LOGICAL_DISPLAY_MAPPER);
        if (c == null) {
            ProbeLog.postNow("LogicalDisplayMapper NOT FOUND");
            return;
        }
        for (Method m : Reflect.methodsMatching(c, Cfg.SNAPSHOT_TRIGGER_METHOD)) {
            final String name = m.getName();
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            ProbeLog.post("DISPLAY-EVENT %s(%s)", name, describeAll(param.args));
                            Snapshots.requestDeferred("display-event:" + name);
                        } catch (Throwable t) {
                            HookEngine.reportOnce("lifecycle:" + name, t);
                        }
                    }
                });
                ProbeLog.postNow("  hooked lifecycle " + Reflect.sig(m));
            } catch (Throwable t) {
                ProbeLog.postNow("  lifecycle hook failed " + name + ": " + t);
            }
        }
    }

    /** Remaining classes worth watching, all optional. */
    private static void hookAssortedByName(ClassLoader cl) {
        String[] classes = {
                DMS,
                DMS_BINDER,
                DMS_LOCAL,
                "com.android.server.display.LogicalDisplay",
                "com.android.server.display.DisplayDevice",
                "com.android.server.display.DisplayPowerController",
                "com.android.server.display.DisplayPowerController2",
                "com.android.server.display.DisplayDeviceConfig",
                "com.android.server.display.mode.SkinThermalStatusObserver",
                "com.android.server.display.RefreshRateController",
                "com.android.server.display.SemRefreshRateController",
                "com.android.server.display.mode.SemDisplayModeDirector",
        };
        for (String name : classes) {
            HookEngine.hookMatching(cl, name, Cfg.HOOKABLE_METHOD, true);
        }
    }

    static String describeAll(Object[] args) {
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
}
