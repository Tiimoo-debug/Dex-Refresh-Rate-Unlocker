package com.tiimoo.dexrefresh.probe;

import android.util.SparseArray;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.Dumper;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.lang.reflect.Field;
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
        String modes = ProbeState.LAST_SEEN.get("modes");
        if (modes != null) {
            sb.append("| modes=").append(modes).append(' ');
        }
        String rrMode = ProbeState.LAST_SEEN.get("refreshRateMode");
        if (rrMode != null) {
            sb.append("| refreshRateMode=").append(rrMode).append(' ');
        }
        String committed = ProbeState.LAST_SEEN.get("committed");
        if (committed != null) {
            sb.append("| committed=").append(committed);
        }
        String restrict = ProbeState.LAST_SEEN.get("restrictHighRefreshRate");
        if (restrict != null) {
            sb.append(" | restrictHRR=").append(restrict);
        }
        return sb.toString();
    }

    /** Vote rendered short: kind plus whatever numbers it carries. */
    static String terse(Object vote) {
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
