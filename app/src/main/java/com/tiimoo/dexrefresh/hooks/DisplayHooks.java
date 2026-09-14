package com.tiimoo.dexrefresh.hooks;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.Dumper;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;
import com.tiimoo.dexrefresh.core.Throttle;
import com.tiimoo.dexrefresh.core.Votes;
import com.tiimoo.dexrefresh.probe.ClassScout;
import com.tiimoo.dexrefresh.probe.CommandPoller;
import com.tiimoo.dexrefresh.probe.Heartbeat;
import com.tiimoo.dexrefresh.probe.ProbeState;
import com.tiimoo.dexrefresh.probe.Snapshots;
import com.tiimoo.dexrefresh.probe.Unlock;

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
        // Start the property command channel first and independently of
        // everything else. On the first device run neither the broadcast
        // receiver nor any snapshot ever fired, so the one control path that
        // has no dependencies must not be behind any of the others.
        CommandPoller.start(cl);
        Heartbeat.start();
        harvestVotePriorities(cl);
        captureServiceInstances(cl);
        hookBootPhase(cl);
        hookVotes(cl);
        hookModeDirector(cl);
        hookLocalDisplayDevice(cl);
        hookSurfaceControl(cl);
        hookDisplayModeConstruction(cl);
        hookDisplayLifecycle(cl);
        hookDisplayInfoCarriers(cl);
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
        ProbeLog.postNow("Vote class = " + vote.getName()
                + (vote.isInterface() ? " (interface - Android 15 style)" : " (class)"));
        int found = collectPriorityConstants(vote);
        ProbeLog.postNow("  " + found + " priority constants on the Vote type");
        // Android 15 keeps an @IntDef holder as Vote$Priority, and Samsung may
        // add its own elsewhere, so sweep the neighbours too.
        for (Class<?> inner : safeInnerClasses(vote)) {
            found += collectPriorityConstants(inner);
        }
        for (String dmdName : DMD_NAMES) {
            Class<?> dmd = Reflect.cls(cl, dmdName);
            if (dmd != null) {
                found += collectPriorityConstants(dmd);
            }
        }
        for (String extra : new String[]{
                "com.android.server.display.mode.VoteSummary",
                "com.android.server.display.mode.VotesStorage"}) {
            Class<?> c = Reflect.cls(cl, extra);
            if (c != null) {
                found += collectPriorityConstants(c);
            }
        }
        if (found == 0) {
            ProbeLog.postNow("!! no PRIORITY constants found anywhere - "
                    + "dumping the raw inventory so we can see what this firmware has");
            dumpAllStaticInts(vote);
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

    private static int collectPriorityConstants(Class<?> c) {
        Field[] fields;
        try {
            fields = c.getDeclaredFields();
        } catch (Throwable t) {
            ProbeLog.postNow("  priority scan failed on " + c.getName() + ": " + t);
            return 0;
        }
        int found = 0;
        for (Field f : fields) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            // "contains" rather than "startsWith": Samsung may prefix its own
            // constants (SEM_PRIORITY_..., or a nested Priority holder).
            if (!f.getName().toUpperCase(Locale.US).contains("PRIORITY")) {
                continue;
            }
            try {
                f.setAccessible(true);
                ProbeState.VOTE_PRIORITY_NAMES.put(f.getInt(null), f.getName());
                found++;
            } catch (Throwable ignored) {
                // constant unreadable; the numeric value still shows in the log
            }
        }
        return found;
    }

    /**
     * Last-resort inventory when no PRIORITY constants were found anywhere.
     *
     * <p>Without this the log just says {@code PRIORITY_?} and we cannot tell
     * whether the harvest broke or Samsung moved the constants somewhere else.
     */
    private static void dumpAllStaticInts(Class<?> c) {
        ProbeLog.postNow("---- every static int on " + c.getName() + " ----");
        try {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    ProbeLog.postNow("   " + f.getName() + " = " + f.getInt(null));
                } catch (Throwable t) {
                    ProbeLog.postNow("   " + f.getName() + " = <unreadable>");
                }
            }
            for (Class<?> inner : c.getDeclaredClasses()) {
                ProbeLog.postNow("   (inner class) " + inner.getName());
            }
        } catch (Throwable t) {
            ProbeLog.postNow("   inventory failed: " + t);
        }
        ProbeLog.postNow("---- end static int inventory ----");
    }

    private static Class<?>[] safeInnerClasses(Class<?> c) {
        try {
            return c.getDeclaredClasses();
        } catch (Throwable t) {
            return new Class<?>[0];
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
                        Votes.setModeDirector(instance);
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
            // Keep handles so Unlock can clear a vote that was filed before it
            // was configured - the cap we are after is filed once and then
            // never touched again, so suppressing future calls alone would
            // leave it in place indefinitely.
            if ("updateVote".equals(methodName) && perDisplay) {
                ProbeState.updateVoteMethod = m;
            } else if ("updateGlobalVote".equals(methodName)) {
                ProbeState.updateGlobalVoteMethod = m;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (ProbeState.votesStorage == null) {
                                ProbeState.votesStorage = param.thisObject;
                            }
                            logVote(label, param.args, perDisplay);
                            maybeSuppress(param, perDisplay);
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
        ProbeLog.post("VOTE %s display=%d priority=%d -> %s%s",
                action, displayId, priority,
                Votes.terse(vote),
                prev == null ? "" : "   [was " + prev + "]");
        if (vote != null) {
            ProbeLog.postBlock(Dumper.dumpShallow("      vote", vote));
        }
        // The caller stack is what cracked restrictHighRefreshRate, and the
        // same trick answers the question run 3 left open: a GLOBAL
        // PhysicalVote(0,60) caps every display including the 144 Hz monitor,
        // and a boolean vote object does not say who filed it. Traced only for
        // global votes and for votes that actually cap physical refresh below
        // 120, so this stays quiet on the per-frame traffic.
        if (vote != null && shouldTraceVote(displayId, vote)
                && Throttle.allow("votetrace:" + displayId + ":" + priority, 6,
                        Cfg.RATE_LIMIT_WINDOW_MS)) {
            ProbeLog.post("   ^ filed by:");
            logCallerStack();
        }
    }

    /**
     * Phase 2, and inert unless the user has named votes to drop.
     *
     * <p>Skipping the original call keeps the vote out of the storage entirely,
     * which is what lets the mode director pick a higher mode. It is the only
     * place in this module that changes behaviour.
     */
    private static void maybeSuppress(XC_MethodHook.MethodHookParam param, boolean perDisplay) {
        if (!Unlock.active() || param.args == null || param.args.length < 1) {
            return;
        }
        int displayId;
        int priority;
        if (perDisplay) {
            if (args3Invalid(param.args)) {
                return;
            }
            displayId = (Integer) param.args[0];
            priority = (Integer) param.args[1];
        } else {
            if (!(param.args[0] instanceof Integer)) {
                return;
            }
            displayId = GLOBAL_DISPLAY_ID;
            priority = (Integer) param.args[0];
        }
        if (!Unlock.shouldDrop(displayId, priority)) {
            return;
        }
        param.setResult(null);
        if (Throttle.allow("suppress:" + displayId + ":" + priority, 5,
                Cfg.RATE_LIMIT_WINDOW_MS)) {
            ProbeLog.post("UNLOCK suppressed vote display=%d priority=%d", displayId, priority);
        }
    }

    private static boolean args3Invalid(Object[] args) {
        return args.length < 2 || !(args[0] instanceof Integer) || !(args[1] instanceof Integer);
    }

    private static boolean shouldTraceVote(int displayId, Object vote) {
        if (displayId == GLOBAL_DISPLAY_ID) {
            return true;
        }
        return Votes.capsPhysicalBelow(vote, 120f);
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
        hookSamsungRestrictor(sc);
        recordCommittedSpecs(cl);
    }

    /**
     * {@code SurfaceControl.restrictHighRefreshRate(boolean)}.
     *
     * <p>Found on the device, absent from AOSP - this is Samsung's own
     * refresh-rate restrictor, the One UI 7 counterpart of the notifyHFRmode
     * call LibreDeX found on One UI 8. The first capture showed it flipping to
     * true and a vote landing on display 0 three milliseconds later, so it is
     * either the cause or is driven by the same decision.
     *
     * <p>Plain argument logging is not enough here: a boolean tells us nothing
     * about <em>who</em> decided. So this hook prints the call stack, which is
     * what connects the restriction to whatever subsystem requested it - DeX,
     * the power manager, or a settings observer. It is rare enough (a handful
     * of calls per screen transition) to afford a stack trace.
     */
    private static void hookSamsungRestrictor(Class<?> surfaceControl) {
        List<Method> methods = Reflect.methodsNamed(surfaceControl, "restrictHighRefreshRate");
        if (methods.isEmpty()) {
            ProbeLog.postNow("SurfaceControl.restrictHighRefreshRate absent on this firmware");
            return;
        }
        for (Method m : methods) {
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String args = describeAll(param.args);
                            if (!Throttle.changed("restrictHRR", args)) {
                                return;
                            }
                            if (!Throttle.allow("restrictHRR", 40, Cfg.RATE_LIMIT_WINDOW_MS)) {
                                return;
                            }
                            ProbeLog.post("RESTRICT-HRR restrictHighRefreshRate(%s)  <-- Samsung", args);
                            ProbeState.record("restrictHighRefreshRate", args);
                            logCallerStack();
                        } catch (Throwable t) {
                            HookEngine.reportOnce("restrictHRR", t);
                        }
                    }
                });
                ProbeLog.postNow("  hooked Samsung SurfaceControl." + Reflect.sig(m)
                        + "  [with caller stack]");
            } catch (Throwable t) {
                ProbeLog.postNow("  restrictHighRefreshRate hook failed: " + t);
            }
        }
    }

    /**
     * Capture the mode list and Samsung's refreshRateMode off any DisplayInfo
     * that passes through.
     *
     * <p>Run 2 proved the panel has a 120 Hz mode (id 1, 1440x3088 @ 120.00001)
     * and that mode 3 is being selected instead, but the mode array printed as
     * a truncated blob. A compact "id@rate" list says at a glance which mode ids
     * are the fast ones.
     *
     * <p>refreshRateMode is a Samsung addition to DisplayInfo, not AOSP. It read
     * 2 while the panel sat at 60, which makes it a candidate for the Motion
     * Smoothness setting - worth watching, not yet proven.
     */
    private static void captureDisplayInfo(Object info) {
        if (info == null) {
            return;
        }
        try {
            // Key everything by display id. These used to be single keys, so
            // whichever display passed through last overwrote the rest - and
            // the built-in panel passes through constantly, which meant the
            // external monitor's state was never visible at all.
            Object displayId = Reflect.get(info, "displayId");
            String key = displayId == null ? "?" : String.valueOf(displayId);
            Object dName = Reflect.get(info, "name");
            if (dName != null) {
                ProbeState.record("name:" + key, String.valueOf(dName));
            }
            Object rrMode = Reflect.get(info, "refreshRateMode");
            if (rrMode != null) {
                ProbeState.record("refreshRateMode:" + key, String.valueOf(rrMode));
            }
            Object modesObj = Reflect.get(info, "supportedModes");
            if (!(modesObj instanceof Object[])) {
                return;
            }
            Object[] modes = (Object[]) modesObj;
            StringBuilder sb = new StringBuilder();
            for (Object mode : modes) {
                Object id = Reflect.call(mode, "getModeId");
                Object rate = Reflect.call(mode, "getRefreshRate");
                if (id == null || rate == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(id).append('@').append(Math.round(
                        ((Number) rate).floatValue()));
            }
            Object active = Reflect.get(info, "modeId");
            if (active != null) {
                sb.append("  active=").append(active);
            }
            ProbeState.record("modes:" + key, sb.toString());

            // Same list with resolutions, for the why report. A monitor often
            // advertises its highest rate only at a lower resolution, so
            // "144 Hz" can mean dropping from 1440p to 1080p - worth knowing
            // before unlocking the rate.
            StringBuilder detail = new StringBuilder();
            for (Object mode : modes) {
                Object id = Reflect.call(mode, "getModeId");
                Object rate = Reflect.call(mode, "getRefreshRate");
                Object w = Reflect.call(mode, "getPhysicalWidth");
                Object h = Reflect.call(mode, "getPhysicalHeight");
                if (id == null || rate == null || w == null || h == null) {
                    continue;
                }
                if (detail.length() > 0) {
                    detail.append(' ');
                }
                detail.append(id).append('@')
                        .append(Math.round(((Number) rate).floatValue()))
                        .append('@').append(w).append('x').append(h);
            }
            ProbeState.record("modedetail:" + key, detail.toString());
        } catch (Throwable t) {
            HookEngine.reportOnce("captureDisplayInfo", t);
        }
    }

    /**
     * Remember the specs last pushed to SurfaceFlinger.
     *
     * <p>This is the ground truth for "what is the panel actually allowed to
     * do", and the heartbeat prints it alongside the votes so one line answers
     * the whole question.
     */
    private static void recordCommittedSpecs(ClassLoader cl) {
        Class<?> ldd = Reflect.cls(cl, LOCAL_DISPLAY_DEVICE);
        if (ldd == null) {
            return;
        }
        for (Method m : Reflect.methodsNamed(ldd, "setDesiredDisplayModeSpecsLocked")) {
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length < 1) {
                                return;
                            }
                            // `this` is the display device, so the specs can be
                            // attributed to a display. SurfaceControl's static
                            // entry point only carries an opaque IBinder, which
                            // is why committed specs were previously recorded
                            // under one key for every display at once.
                            ProbeState.record("committed:" + deviceLabel(param.thisObject),
                                    Dumper.describe(param.args[0], true));
                        } catch (Throwable t) {
                            HookEngine.reportOnce("committed", t);
                        }
                    }
                });
            } catch (Throwable ignored) {
                // already hooked for logging; recording is a bonus
            }
        }
    }

    /** Short identity for a DisplayDevice: its name, else its uniqueId. */
    static String deviceLabel(Object device) {
        if (device == null) {
            return "?";
        }
        Object info = Reflect.get(device, "mCurrentDisplayDeviceInfo");
        if (info == null) {
            info = Reflect.get(device, "mDisplayDeviceInfo");
        }
        Object name = info == null ? null : Reflect.get(info, "name");
        if (name != null) {
            return String.valueOf(name);
        }
        Object uniqueId = Reflect.get(device, "mUniqueId");
        return uniqueId == null ? "?" : String.valueOf(uniqueId);
    }

    /** Print who called us, skipping our own frames. */
    private static void logCallerStack() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        ProbeLog.post("   caller stack:");
        int printed = 0;
        for (StackTraceElement f : frames) {
            String cn = f.getClassName();
            if (cn.startsWith("com.tiimoo.dexrefresh")
                    || cn.startsWith("de.robv.android.xposed")
                    || cn.startsWith("org.lsposed")) {
                continue;
            }
            ProbeLog.post("      at %s.%s(%s:%d)", cn, f.getMethodName(),
                    f.getFileName(), f.getLineNumber());
            if (++printed >= 25) {
                break;
            }
        }
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
                            for (Object a : param.args == null ? new Object[0] : param.args) {
                                if (a != null && a.getClass().getName().endsWith("DisplayInfo")) {
                                    captureDisplayInfo(a);
                                }
                            }
                            // Also to the report tag: whether a display was
                            // even detected is the first question when a dock
                            // shows power and ethernet but no picture, and it
                            // must be answerable without turning on the
                            // per-call firehose.
                            ProbeLog.postReport(String.format(java.util.Locale.US,
                                    "DISPLAY-EVENT %s(%s)%s", name,
                                    describeAll(param.args), identifyDevices(param.args)));
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
    /** Any hooked method carrying a DisplayInfo feeds the mode-list capture. */
    private static void hookDisplayInfoCarriers(ClassLoader cl) {
        Class<?> ldd = Reflect.cls(cl, LOCAL_DISPLAY_DEVICE);
        if (ldd == null) {
            return;
        }
        for (Method m : Reflect.methodsNamed(ldd, "updateDisplayInfoForFrameRateOverride")) {
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) {
                            return;
                        }
                        for (Object a : param.args) {
                            if (a != null && a.getClass().getName().endsWith("DisplayInfo")) {
                                captureDisplayInfo(a);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

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

    /**
     * Name/uniqueId/type for any DisplayDevice among the arguments.
     *
     * <p>"DisplayDevice@3f2a1b" tells us nothing; which physical or virtual
     * display DeX just added tells us a lot. Field reads only - calling
     * getDisplayDeviceInfoLocked() here would take the display lock from inside
     * a hook that is already under it.
     */
    private static String identifyDevices(Object[] args) {
        if (args == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (a == null || !a.getClass().getName().contains("DisplayDevice")) {
                continue;
            }
            Object info = Reflect.get(a, "mCurrentDisplayDeviceInfo");
            if (info == null) {
                info = Reflect.get(a, "mDisplayDeviceInfo");
            }
            String name = info != null ? String.valueOf(Reflect.get(info, "name")) : null;
            Object uniqueId = Reflect.get(a, "mUniqueId");
            sb.append("  [device");
            if (name != null) {
                sb.append(" name=").append(name);
            }
            if (uniqueId != null) {
                sb.append(" uniqueId=").append(uniqueId);
            }
            if (info != null) {
                Object type = Reflect.get(info, "type");
                Object flags = Reflect.get(info, "flags");
                if (type != null) {
                    sb.append(" type=").append(type);
                }
                if (flags != null) {
                    sb.append(" flags=0x").append(Integer.toHexString(
                            flags instanceof Integer ? (Integer) flags : 0));
                }
            }
            sb.append(']');
        }
        return sb.toString();
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
