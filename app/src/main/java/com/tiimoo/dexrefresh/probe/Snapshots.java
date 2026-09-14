package com.tiimoo.dexrefresh.probe;

import android.util.SparseArray;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.Dumper;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Point-in-time dumps of everything that decides the refresh rate.
 *
 * <p>The intended workflow is one snapshot with the phone idle/mirrored and one
 * with DeX running, then diff the two. See docs/USAGE.md.
 *
 * <h3>Threading</h3>
 * Snapshots run on a dedicated single thread and never from a hook callback.
 * This matters: reading display state goes through DisplayManagerGlobal, which
 * issues a binder call into DisplayManagerService and takes its lock. Doing
 * that from a hook that is already holding that lock deadlocks system_server
 * and the watchdog reboots the phone. On our own thread we hold nothing, so the
 * call is safe.
 */
public final class Snapshots {

    private Snapshots() {
    }

    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, Cfg.TAG + "-snap");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final AtomicBoolean DEFERRED_PENDING = new AtomicBoolean();
    private static volatile long lastAutoSnapshotMs;

    /** Run work off the hook thread. */
    public static void runLater(long delayMs, Runnable r) {
        try {
            final Runnable work = r;
            EXEC.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        work.run();
                    } catch (Throwable t) {
                        ProbeLog.postThrowable("snapshot task", t);
                    }
                }
            }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            ProbeLog.postThrowable("schedule", t);
        }
    }

    /**
     * Ask for a snapshot once things settle. Coalesced: a burst of display
     * events produces one snapshot of the settled state, not ten of a state in
     * flux.
     */
    public static void requestDeferred(String label) {
        long now = System.currentTimeMillis();
        if (now - lastAutoSnapshotMs < Cfg.SNAPSHOT_MIN_INTERVAL_MS) {
            // A docking sequence fires display events in a burst; one dump of
            // the settled state is worth more than ten of a state in flux.
            return;
        }
        if (!DEFERRED_PENDING.compareAndSet(false, true)) {
            return;
        }
        final String snapshotLabel = label;
        runLater(Cfg.SNAPSHOT_SETTLE_MS, new Runnable() {
            @Override
            public void run() {
                DEFERRED_PENDING.set(false);
                lastAutoSnapshotMs = System.currentTimeMillis();
                take(snapshotLabel, Cfg.SNAPSHOT_DEPTH, false);
            }
        });
    }

    /** Take a snapshot on the snapshot thread. */
    public static void request(final String label, final int depth, final boolean callGetters) {
        runLater(0, new Runnable() {
            @Override
            public void run() {
                take(label, depth, callGetters);
            }
        });
    }

    /**
     * @param callGetters when true the snapshot also invokes zero-arg getters
     *                    such as getDesiredDisplayModeSpecs. Off by default:
     *                    those take mode-director locks, and while that is safe
     *                    from this thread it is one more way to perturb the
     *                    thing we are trying to observe.
     */
    public static void take(String label, int depth, boolean callGetters) {
        int n = COUNTER.incrementAndGet();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("\n========== SNAPSHOT #").append(n)
                .append(" label='").append(label).append('\'')
                .append(" t=").append(System.currentTimeMillis())
                .append(" ==========\n");
        try {
            sb.append("[dex-hints] ").append(DeXState.hints()).append('\n');
        } catch (Throwable t) {
            sb.append("[dex-hints] failed: ").append(t).append('\n');
        }

        // The priority table and SurfaceControl inventory are printed at boot,
        // but the first device run showed boot output long gone from the logcat
        // ring buffer by the time anyone looks. Repeat them in every snapshot so
        // one capture is self-contained.
        section(sb, "vote-priority-table", new Section() {
            @Override
            public String render() {
                return dumpPriorityTable();
            }
        });
        section(sb, "displays", new Section() {
            @Override
            public String render() {
                return dumpDisplays(depth);
            }
        });
        section(sb, "votes", new Section() {
            @Override
            public String render() {
                return dumpVotes(depth);
            }
        });
        section(sb, "mode-director", new Section() {
            @Override
            public String render() {
                return dumpModeDirector(depth, callGetters);
            }
        });
        section(sb, "last-observed", new Section() {
            @Override
            public String render() {
                return dumpLastSeen();
            }
        });
        section(sb, "samsung-surfacecontrol", new Section() {
            @Override
            public String render() {
                return dumpSurfaceControlInventory();
            }
        });

        sb.append("========== END SNAPSHOT #").append(n).append(" ==========");
        ProbeLog.postReport(sb.toString());
    }

    private interface Section {
        String render() throws Throwable;
    }

    private static void section(StringBuilder sb, String name, Section s) {
        sb.append("--- [").append(name).append("] ---\n");
        try {
            String body = s.render();
            sb.append(body == null ? "(nothing)\n" : body);
            if (body != null && !body.endsWith("\n")) {
                sb.append('\n');
            }
        } catch (Throwable t) {
            sb.append("!! section failed: ").append(t).append('\n');
        }
    }

    // ------------------------------------------------------------------
    // Displays and their mode lists
    // ------------------------------------------------------------------

    private static String dumpDisplays(int depth) {
        ClassLoader cl = classLoader();
        StringBuilder sb = new StringBuilder();
        Class<?> dmg = Reflect.cls(cl, "android.hardware.display.DisplayManagerGlobal");
        if (dmg == null) {
            return "DisplayManagerGlobal not found\n";
        }
        Object global;
        try {
            Method getInstance = Reflect.method(dmg, "getInstance");
            global = getInstance == null ? null : getInstance.invoke(null);
        } catch (Throwable t) {
            return "DisplayManagerGlobal.getInstance failed: " + t + "\n";
        }
        if (global == null) {
            return "DisplayManagerGlobal instance null\n";
        }

        int[] ids = displayIds(dmg, global);
        if (ids == null) {
            return "could not enumerate display ids\n";
        }
        Method getDisplayInfo = Reflect.method(dmg, "getDisplayInfo", int.class);
        if (getDisplayInfo == null) {
            return "DisplayManagerGlobal.getDisplayInfo(int) not found\n";
        }
        for (int id : ids) {
            sb.append("display ").append(id).append(":\n");
            Object info;
            try {
                info = getDisplayInfo.invoke(global, id);
            } catch (Throwable t) {
                sb.append("   getDisplayInfo failed: ").append(t).append('\n');
                continue;
            }
            if (info == null) {
                sb.append("   <null DisplayInfo>\n");
                continue;
            }
            appendScalar(sb, info, "name");
            appendScalar(sb, info, "uniqueId");
            appendScalar(sb, info, "type");
            appendScalar(sb, info, "state");
            appendScalar(sb, info, "flags");
            appendScalar(sb, info, "modeId");
            appendScalar(sb, info, "defaultModeId");
            appendScalar(sb, info, "renderFrameRate");
            appendScalar(sb, info, "logicalWidth");
            appendScalar(sb, info, "logicalHeight");

            Object modes = Reflect.get(info, "supportedModes");
            if (modes instanceof Object[]) {
                Object[] arr = (Object[]) modes;
                sb.append("   supportedModes (").append(arr.length).append("):\n");
                for (Object m : arr) {
                    sb.append("      ").append(renderMode(m)).append('\n');
                }
            } else {
                sb.append("   supportedModes = ").append(Dumper.describe(modes, true)).append('\n');
            }
            // Anything else refresh-related that this firmware carries on
            // DisplayInfo but AOSP does not.
            sb.append(indent(Dumper.dump("   DisplayInfo fields", info,
                    Math.min(depth, 2), true)));
        }
        return sb.toString();
    }

    private static int[] displayIds(Class<?> dmg, Object global) {
        for (Method m : Reflect.methodsNamed(dmg, "getDisplayIds")) {
            try {
                Object r;
                if (m.getParameterTypes().length == 0) {
                    r = m.invoke(global);
                } else if (m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == boolean.class) {
                    // includeDisabled == true, so a display DeX has parked
                    // still shows up.
                    r = m.invoke(global, true);
                } else {
                    continue;
                }
                if (r instanceof int[]) {
                    return (int[]) r;
                }
            } catch (Throwable ignored) {
                // try the next overload
            }
        }
        return null;
    }

    private static void appendScalar(StringBuilder sb, Object info, String field) {
        Object v = Reflect.get(info, field);
        if (v != null) {
            sb.append("   ").append(field).append(" = ")
                    .append(Dumper.describe(v, true)).append('\n');
        }
    }

    /** Render a Display.Mode through its getters, falling back to toString. */
    public static String renderMode(Object mode) {
        if (mode == null) {
            return "null";
        }
        Object id = Reflect.call(mode, "getModeId");
        Object w = Reflect.call(mode, "getPhysicalWidth");
        Object h = Reflect.call(mode, "getPhysicalHeight");
        Object rate = Reflect.call(mode, "getRefreshRate");
        if (id == null || w == null || h == null || rate == null) {
            return Dumper.describe(mode, true);
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.US,
                "mode id=%s %sx%s @%s", id, w, h, Dumper.hz(rate)));
        Object alt = Reflect.call(mode, "getAlternativeRefreshRates");
        if (alt != null) {
            sb.append(" alt=").append(Dumper.describe(alt, true));
        }
        Object vsync = Reflect.call(mode, "getVsyncRate");
        if (vsync != null) {
            sb.append(" vsync=").append(Dumper.hz(vsync));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // The vote table
    // ------------------------------------------------------------------

    /**
     * Render the live vote table: display id -> priority -> vote.
     *
     * <p>This is the payload of the whole exercise. Diff the DeX-on snapshot
     * against the DeX-off one and the vote that appears (or tightens) is the
     * thing capping the panel.
     */
    private static String dumpVotes(int depth) {
        Object dmd = modeDirector();
        if (dmd == null) {
            return "DisplayModeDirector instance not captured\n";
        }
        Object storage = Reflect.findByTypeFragment(dmd, "VotesStorage");
        Object votesByDisplay = null;
        if (storage != null) {
            votesByDisplay = Reflect.findByTypeFragment(storage, "SparseArray");
        }
        if (votesByDisplay == null) {
            // Pre-VotesStorage layout keeps the map on the director itself.
            votesByDisplay = Reflect.findByTypeFragment(dmd, "SparseArray");
        }
        if (!(votesByDisplay instanceof SparseArray)) {
            return "vote map not found (storage=" + Dumper.describe(storage, false)
                    + "); see the mode-director section for the raw field dump\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("source: ").append(storage == null ? "DisplayModeDirector" : "VotesStorage")
                .append('\n');
        SparseArray<?> outer = (SparseArray<?>) votesByDisplay;
        for (int i = 0; i < outer.size(); i++) {
            int displayId = outer.keyAt(i);
            sb.append("display ").append(displayId)
                    .append(displayId < 0 ? " (GLOBAL)" : "").append(":\n");
            Object inner = outer.valueAt(i);
            if (!(inner instanceof SparseArray)) {
                sb.append("   ").append(Dumper.describe(inner, true)).append('\n');
                continue;
            }
            SparseArray<?> votes = (SparseArray<?>) inner;
            if (votes.size() == 0) {
                sb.append("   (no votes)\n");
            }
            for (int j = 0; j < votes.size(); j++) {
                int priority = votes.keyAt(j);
                Object vote = votes.valueAt(j);
                sb.append(String.format(Locale.US, "   priority %3d %-34s %s%n",
                        priority, ProbeState.votePriorityName(priority),
                        Heartbeat.terse(vote)));
                sb.append(String.format(Locale.US, "       raw: %s%n",
                        Dumper.describe(vote, true)));
                if (vote != null && depth > 1) {
                    sb.append(indent(Dumper.dump("      vote fields", vote,
                            Math.min(depth, 2), true)));
                }
            }
        }
        return sb.toString();
    }

    private static String dumpModeDirector(int depth, boolean callGetters) {
        Object dmd = modeDirector();
        if (dmd == null) {
            return "not captured\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(Dumper.dump("DisplayModeDirector", dmd, depth, true));
        if (callGetters) {
            Method m = Reflect.method(dmd.getClass(), "getDesiredDisplayModeSpecs", int.class);
            if (m != null) {
                // Use the display ids that actually exist. This was hardcoded
                // to {0,1,2}, so the external display - which has shown up as
                // 6 and as 7 on this device - was never queried at all.
                for (int displayId : knownDisplayIds()) {
                    try {
                        Object specs = m.invoke(dmd, displayId);
                        sb.append("getDesiredDisplayModeSpecs(").append(displayId)
                                .append(") = ").append(Dumper.describe(specs, true)).append('\n');
                    } catch (Throwable t) {
                        sb.append("getDesiredDisplayModeSpecs(").append(displayId)
                                .append(") threw ").append(t).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * This firmware's own vote priority numbering.
     *
     * <p>Without it the vote log reads "priority=7(PRIORITY_?)" and there is no
     * way to know whether 7 means what AOSP 15 calls 7.
     */
    private static String dumpPriorityTable() {
        if (ProbeState.VOTE_PRIORITY_NAMES.isEmpty()) {
            return "EMPTY - the harvest found no PRIORITY constants. Vote priority\n"
                    + "numbers in the log are raw and cannot be trusted to match AOSP.\n"
                    + "Check the boot log for the raw static-int inventory.\n";
        }
        List<Integer> keys = new ArrayList<>(ProbeState.VOTE_PRIORITY_NAMES.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        sb.append(keys.size()).append(" priorities on this firmware")
                .append(" (AOSP 15 has 21, 0..20)\n");
        for (Integer k : keys) {
            sb.append(String.format(Locale.US, "   %3d = %s%n",
                    k, ProbeState.VOTE_PRIORITY_NAMES.get(k)));
        }
        return sb.toString();
    }

    /**
     * Samsung's non-AOSP SurfaceControl entry points.
     *
     * <p>restrictHighRefreshRate(boolean) turned up here on One UI 7 and is the
     * most interesting thing the probe has found, so keep the inventory in front
     * of us rather than only in the boot log.
     */
    private static String dumpSurfaceControlInventory() {
        Class<?> sc = Reflect.cls(classLoader(), "android.view.SurfaceControl");
        if (sc == null) {
            return "SurfaceControl not found\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Method m : Reflect.methodsMatching(sc, Cfg.INTERESTING)) {
            sb.append("   ").append(Reflect.sig(m)).append('\n');
        }
        String lastRestrict = ProbeState.LAST_SEEN.get("restrictHighRefreshRate");
        if (lastRestrict != null) {
            sb.append("   >> last restrictHighRefreshRate arg = ")
                    .append(lastRestrict).append('\n');
        }
        return sb.length() == 0 ? "(no matching methods)\n" : sb.toString();
    }

    /** Display ids present in the vote map, plus 0 as a floor. */
    static int[] knownDisplayIds() {
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<Integer>();
        ids.add(0);
        try {
            Object dmd = modeDirector();
            Object storage = dmd == null ? null : Reflect.findByTypeFragment(dmd, "VotesStorage");
            Object byDisplay = storage != null
                    ? Reflect.findByTypeFragment(storage, "SparseArray")
                    : null;
            if (byDisplay instanceof SparseArray) {
                SparseArray<?> outer = (SparseArray<?>) byDisplay;
                for (int i = 0; i < outer.size(); i++) {
                    int id = outer.keyAt(i);
                    if (id >= 0) {
                        ids.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall back to just display 0
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) {
            out[i++] = id;
        }
        return out;
    }

    private static String dumpLastSeen() {
        if (ProbeState.LAST_SEEN.isEmpty()) {
            return "(nothing observed yet)\n";
        }
        List<String> keys = new ArrayList<>(ProbeState.LAST_SEEN.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append("   ").append(k).append(" = ")
                    .append(ProbeState.LAST_SEEN.get(k)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Instance resolution, with fallbacks for a missed constructor hook
    // ------------------------------------------------------------------

    static ClassLoader classLoader() {
        ClassLoader cl = ProbeState.systemServerClassLoader;
        return cl != null ? cl : Snapshots.class.getClassLoader();
    }

    /** DisplayManagerService, via the constructor capture or LocalServices. */
    public static Object displayManagerService() {
        Object dms = ProbeState.displayManagerService;
        if (dms != null) {
            return dms;
        }
        ClassLoader cl = classLoader();
        try {
            Class<?> localServices = Reflect.cls(cl, "com.android.server.LocalServices");
            Class<?> internalIface =
                    Reflect.cls(cl, "android.hardware.display.DisplayManagerInternal");
            Method get = Reflect.method(localServices, "getService", Class.class);
            if (get != null && internalIface != null) {
                Object internal = get.invoke(null, internalIface);
                // DisplayManagerService$LocalService is a non-static inner class,
                // so this$0 is the service itself.
                Object outer = Reflect.get(internal, "this$0");
                if (outer != null) {
                    ProbeState.displayManagerService = outer;
                    return outer;
                }
            }
        } catch (Throwable t) {
            ProbeLog.postThrowable("resolve DisplayManagerService", t);
        }
        return null;
    }

    /** DisplayModeDirector, via the capture or by searching the DMS fields. */
    public static Object modeDirector() {
        Object dmd = ProbeState.displayModeDirector;
        if (dmd != null) {
            return dmd;
        }
        Object dms = displayManagerService();
        if (dms == null) {
            return null;
        }
        dmd = Reflect.findByTypeFragment(dms, "DisplayModeDirector");
        if (dmd != null) {
            ProbeState.displayModeDirector = dmd;
        }
        return dmd;
    }

    private static String indent(String block) {
        if (block == null || block.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (!line.isEmpty()) {
                sb.append("   ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Exposed so the control receiver can report what it knows. */
    public static Map<String, String> lastSeen() {
        return ProbeState.LAST_SEEN;
    }
}
