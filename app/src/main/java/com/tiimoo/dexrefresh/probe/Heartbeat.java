package com.tiimoo.dexrefresh.probe;

import android.util.SparseArray;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.Dumper;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Periodic compact dump of the whole refresh-rate state.
 *
 * <p>This exists because of a blind spot the first device run exposed. The vote
 * log is change-triggered, which is what keeps it readable at frame rate - but
 * it means a constraint that is <em>always</em> present and never moves is
 * never printed at all. In run 1 every display sat at a 60 Hz ceiling for the
 * entire capture, so the one thing we most needed to see was the one thing the
 * log could not show.
 *
 * <p>So: print the full table on a timer, not on change. Every tick that
 * differs from the last is logged, plus a forced anchor line periodically even
 * when nothing changed, so a constant ceiling is visible as a constant rather
 * than as silence.
 *
 * <h3>Threading</h3>
 * Own daemon thread, and field reads only - no method calls into the mode
 * director, no binder. Reading the vote SparseArray without holding the
 * director's lock can in principle catch a torn update; for a probe that is an
 * acceptable trade against the deadlock risk of taking that lock, and every
 * read is wrapped.
 */
public final class Heartbeat {

    private Heartbeat() {
    }

    private static volatile boolean started;
    private static String lastLine;
    private static long lastForcedMs;

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, Cfg.TAG + "-beat");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void loop() {
        sleep(25000L);
        while (true) {
            try {
                tick();
            } catch (Throwable t) {
                ProbeLog.postThrowable("heartbeat", t);
            }
            sleep(Cfg.HEARTBEAT_MS);
        }
    }

    private static void tick() {
        // Auto mode re-derives the selection from the live vote table. Done
        // here because this thread already holds no locks and runs regularly,
        // and because displays appear, churn and vanish as DeX is docked.
        try {
            Unlock.refreshAuto();
        } catch (Throwable t) {
            ProbeLog.postThrowable("auto unlock refresh", t);
        }
        String line = state();
        long now = System.currentTimeMillis();
        boolean changed = !line.equals(lastLine);
        boolean forced = now - lastForcedMs >= Cfg.HEARTBEAT_ANCHOR_MS;
        if (!changed && !forced) {
            return;
        }
        lastLine = line;
        if (forced) {
            lastForcedMs = now;
        }
        ProbeLog.post("STATE%s %s", changed ? "*" : " ", line);
    }

    /**
     * One line: every display's votes, plus what was last committed.
     *
     * <p>Format is deliberately terse so a long capture stays greppable:
     * {@code d0{p7=Render(60,inf)} d2{} | committed=<last SurfaceControl specs>}
     */
    static String state() {
        StringBuilder sb = new StringBuilder();
        Object dmd = Snapshots.modeDirector();
        if (dmd == null) {
            sb.append("<no mode director>");
        } else {
            Object storage = Reflect.findByTypeFragment(dmd, "VotesStorage");
            Object byDisplay = storage != null
                    ? Reflect.findByTypeFragment(storage, "SparseArray")
                    : Reflect.findByTypeFragment(dmd, "SparseArray");
            if (!(byDisplay instanceof SparseArray)) {
                sb.append("<no vote map>");
            } else {
                SparseArray<?> outer = (SparseArray<?>) byDisplay;
                for (int i = 0; i < outer.size(); i++) {
                    sb.append('d').append(outer.keyAt(i)).append('{');
                    Object inner = outer.valueAt(i);
                    if (inner instanceof SparseArray) {
                        SparseArray<?> votes = (SparseArray<?>) inner;
                        for (int j = 0; j < votes.size(); j++) {
                            if (j > 0) {
                                sb.append(' ');
                            }
                            int priority = votes.keyAt(j);
                            sb.append('p').append(priority).append('=')
                                    .append(terse(votes.valueAt(j)));
                        }
                    }
                    sb.append("} ");
                }
            }
        }
        // Every display, not just whichever one wrote last. The external
        // monitor is the whole point of the exercise and was previously
        // invisible here, drowned out by the built-in panel.
        appendByPrefix(sb, "name:", "names");
        appendByPrefix(sb, "modes:", "modes");
        appendByPrefix(sb, "refreshRateMode:", "rrMode");
        appendByPrefix(sb, "committed:", "committed");
        if (Unlock.active()) {
            sb.append("| UNLOCK ").append(Unlock.describe()).append(' ');
        }
        String restrict = ProbeState.LAST_SEEN.get("restrictHighRefreshRate");
        if (restrict != null) {
            sb.append(" | restrictHRR=").append(restrict);
        }
        return sb.toString();
    }

    /**
     * Display ids the framework currently reports, or null if unavailable.
     *
     * <p>Dead display generations linger: the HDMI monitor churned through ids
     * 6, 7, 8, 9 in one session and all four kept showing a frozen "active="
     * from when they existed. d6 in particular still read 60 Hz long after the
     * live display had moved to 120, which is actively misleading. Ask the
     * framework which ids are real and mark the rest.
     *
     * <p>Safe from this thread: it is a binder call into DisplayManagerService,
     * and the heartbeat holds no locks. It must never be done from a hook.
     */
    private static java.util.Set<Integer> liveDisplayIds() {
        try {
            Class<?> dmg = Reflect.cls(ProbeState.systemServerClassLoader,
                    "android.hardware.display.DisplayManagerGlobal");
            if (dmg == null) {
                return null;
            }
            java.lang.reflect.Method getInstance = Reflect.method(dmg, "getInstance");
            Object global = getInstance == null ? null : getInstance.invoke(null);
            if (global == null) {
                return null;
            }
            for (java.lang.reflect.Method m : Reflect.methodsNamed(dmg, "getDisplayIds")) {
                Object r;
                if (m.getParameterTypes().length == 0) {
                    r = m.invoke(global);
                } else if (m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == boolean.class) {
                    r = m.invoke(global, true);
                } else {
                    continue;
                }
                if (r instanceof int[]) {
                    java.util.Set<Integer> out = new java.util.HashSet<Integer>();
                    for (int id : (int[]) r) {
                        out.add(id);
                    }
                    return out;
                }
            }
        } catch (Throwable ignored) {
            // fall back to showing everything
        }
        return null;
    }

    /** Append every recorded entry sharing a prefix, sorted for stable diffs. */
    private static void appendByPrefix(StringBuilder sb, String prefix, String label) {
        java.util.Set<Integer> live = liveDisplayIds();
        List<String> keys = new ArrayList<String>();
        for (String k : ProbeState.LAST_SEEN.keySet()) {
            if (!k.startsWith(prefix)) {
                continue;
            }
            // Hide entries for displays that no longer exist, so a dead
            // generation's frozen "active=" cannot be read as current state.
            if (live != null) {
                try {
                    int id = Integer.parseInt(k.substring(prefix.length()));
                    if (!live.contains(id)) {
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                    // not a display-id key (committed entries are keyed by name)
                }
            }
            keys.add(k);
        }
        if (keys.isEmpty()) {
            return;
        }
        Collections.sort(keys);
        sb.append("| ").append(label).append('{');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(keys.get(i).substring(prefix.length())).append('=')
                    .append(ProbeState.LAST_SEEN.get(keys.get(i)));
        }
        sb.append("} ");
    }

    /** Vote rendered short: kind plus whatever numbers it carries. */
    public static String terse(Object vote) {
        return terse(vote, 0);
    }

    /**
     * @param depth recursion guard for CombinedVote, which nests other votes
     */
    private static String terse(Object vote, int depth) {
        if (vote == null) {
            return "null";
        }
        try {
            String kind = Reflect.simple(vote.getClass());
            int dollar = kind.lastIndexOf('$');
            if (dollar >= 0) {
                kind = kind.substring(dollar + 1);
            }
            // CombinedVote wraps a List<Vote> and applies each in turn, so the
            // wrapper's name says nothing about what it actually constrains.
            // Printing only the wrapper is what hid the 60 Hz ceiling in run 2:
            // the visible votes alone implied 120 Hz, yet 60 was committed, so
            // the real constraint had to be inside one of these.
            if (depth < 3) {
                List<?> nested = nestedVotes(vote);
                if (nested != null) {
                    StringBuilder sb = new StringBuilder(kind).append('[');
                    for (int i = 0; i < nested.size(); i++) {
                        if (i > 0) {
                            sb.append(' ');
                        }
                        sb.append(terse(nested.get(i), depth + 1));
                    }
                    return sb.append(']').toString();
                }
            }
            Object min = Reflect.get(vote, "mMinRefreshRate");
            Object max = Reflect.get(vote, "mMaxRefreshRate");
            if (min != null || max != null) {
                return kind + "(" + num(min) + "," + num(max) + ")";
            }
            Object width = Reflect.get(vote, "mWidth");
            Object height = Reflect.get(vote, "mHeight");
            if (width != null && height != null) {
                return kind + "(" + width + "x" + height + ")";
            }
            Object modeIds = Reflect.get(vote, "mModeIds");
            if (modeIds != null) {
                return kind + Dumper.describe(modeIds, true);
            }
            Object base = Reflect.get(vote, "mBaseModeRefreshRate");
            if (base != null) {
                return kind + "(" + num(base) + ")";
            }
            return kind;
        } catch (Throwable t) {
            return "<?>";
        }
    }

    /** The List<Vote> inside a CombinedVote, whatever the field is called. */
    private static List<?> nestedVotes(Object vote) {
        for (Class<?> k = vote.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                return null;
            }
            for (Field f : fields) {
                if (!List.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(vote);
                    if (v instanceof List && !((List<?>) v).isEmpty()) {
                        return (List<?>) v;
                    }
                } catch (Throwable ignored) {
                    // not readable; keep looking
                }
            }
        }
        return null;
    }

    private static String num(Object o) {
        if (!(o instanceof Number)) {
            return String.valueOf(o);
        }
        double d = ((Number) o).doubleValue();
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
