package com.tiimoo.dexrefresh.probe;

import android.util.SparseArray;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;
import com.tiimoo.dexrefresh.core.Votes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        publishState();
    }

    /**
     * Publish a terse summary the control panel can read without root.
     *
     * <p>An app can read a debug.* property unprivileged, so the panel shows
     * live state with no su round-trip. Property values cap out around 92
     * bytes, so this is per-display "activeRate/maxRate" plus the unlock state,
     * truncated hard.
     */
    private static void publishState() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(Unlock.isAuto() ? "auto" : Unlock.active() ? "on" : "off");
            for (String key : new java.util.TreeSet<String>(ProbeState.LAST_SEEN.keySet())) {
                if (!key.startsWith("modes:")) {
                    continue;
                }
                String modes = ProbeState.LAST_SEEN.get(key);
                if (modes == null) {
                    continue;
                }
                sb.append(' ').append(key.substring("modes:".length())).append('=')
                        .append(activeRate(modes)).append('/').append(maxRate(modes));
            }
            String value = sb.toString();
            if (value.length() > 90) {
                value = value.substring(0, 90);
            }
            Class<?> sp = Reflect.cls(ProbeState.systemServerClassLoader,
                    "android.os.SystemProperties");
            java.lang.reflect.Method set = sp == null ? null
                    : Reflect.method(sp, "set", String.class, String.class);
            if (set != null) {
                set.invoke(null, Cfg.PROP_STATE, value);
            }
        } catch (Throwable ignored) {
            // SELinux may refuse the write; the panel falls back to the log.
        }
    }

    /** "1@120 3@60 active=3" -> "60" */
    private static String activeRate(String modes) {
        int at = modes.indexOf("active=");
        if (at < 0) {
            return "?";
        }
        String activeId = modes.substring(at + 7).trim();
        for (String token : modes.split("\\s+")) {
            String[] bits = token.split("@");
            if (bits.length >= 2 && bits[0].equals(activeId)) {
                return bits[1];
            }
        }
        return "?";
    }

    /** "1@120 3@60 active=3" -> "120" */
    private static String maxRate(String modes) {
        int best = 0;
        for (String token : modes.split("\\s+")) {
            String[] bits = token.split("@");
            if (bits.length < 2) {
                continue;
            }
            try {
                best = Math.max(best, Integer.parseInt(bits[1]));
            } catch (NumberFormatException ignored) {
                // not a mode token
            }
        }
        return best == 0 ? "?" : String.valueOf(best);
    }

    /**
     * One line: every display's votes, plus what was last committed.
     *
     * <p>Format is deliberately terse so a long capture stays greppable:
     * {@code d0{p7=RenderVote(60,inf)} d2{} | committed=...}
     */
    static String state() {
        StringBuilder sb = new StringBuilder();
        SparseArray<?> byDisplay = Votes.byDisplay();
        if (byDisplay == null) {
            sb.append("<no vote map>");
        } else {
            for (int i = 0; i < byDisplay.size(); i++) {
                sb.append('d').append(byDisplay.keyAt(i)).append('{');
                Object inner = byDisplay.valueAt(i);
                if (inner instanceof SparseArray) {
                    SparseArray<?> votes = (SparseArray<?>) inner;
                    for (int j = 0; j < votes.size(); j++) {
                        if (j > 0) {
                            sb.append(' ');
                        }
                        sb.append('p').append(votes.keyAt(j)).append('=')
                                .append(Votes.terse(votes.valueAt(j)));
                    }
                }
                sb.append("} ");
            }
        }
        appendByPrefix(sb, "name:", "names");
        appendByPrefix(sb, "modes:", "modes");
        appendByPrefix(sb, "refreshRateMode:", "rrMode");
        appendByPrefix(sb, "committed:", "committed");
        if (Unlock.active() || Unlock.isAuto()) {
            sb.append("| UNLOCK ").append(Unlock.describe()).append(' ');
        }
        String restrict = ProbeState.LAST_SEEN.get("restrictHighRefreshRate");
        if (restrict != null) {
            sb.append("| restrictHRR=").append(restrict);
        }
        return sb.toString();
    }

    /**
     * Display ids the framework currently reports, or null if unavailable.
     *
     * <p>Dead display generations linger: the HDMI monitor churned through ids
     * 6, 7, 8, 9 in one session and all four kept showing a frozen "active="
     * from when they existed, which is actively misleading. Ask the framework
     * which ids are real and drop the rest.
     *
     * <p>Safe from this thread: it is a binder call into DisplayManagerService
     * and the heartbeat holds no locks. It must never be done from a hook.
     */
    private static Set<Integer> liveDisplayIds() {
        try {
            Class<?> dmg = Reflect.cls(ProbeState.systemServerClassLoader,
                    "android.hardware.display.DisplayManagerGlobal");
            if (dmg == null) {
                return null;
            }
            Method getInstance = Reflect.method(dmg, "getInstance");
            Object global = getInstance == null ? null : getInstance.invoke(null);
            if (global == null) {
                return null;
            }
            for (Method m : Reflect.methodsNamed(dmg, "getDisplayIds")) {
                Class<?>[] params = m.getParameterTypes();
                Object result;
                if (params.length == 0) {
                    result = m.invoke(global);
                } else if (params.length == 1 && params[0] == boolean.class) {
                    result = m.invoke(global, Boolean.TRUE);
                } else {
                    continue;
                }
                if (result instanceof int[]) {
                    Set<Integer> out = new HashSet<Integer>();
                    for (int id : (int[]) result) {
                        out.add(Integer.valueOf(id));
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
        Set<Integer> live = liveDisplayIds();
        List<String> keys = new ArrayList<String>();
        for (String key : ProbeState.LAST_SEEN.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            if (live != null && !isLive(key.substring(prefix.length()), live)) {
                continue;
            }
            keys.add(key);
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

    /** Keys that are not display ids (committed is keyed by device name) always pass. */
    private static boolean isLive(String suffix, Set<Integer> live) {
        try {
            return live.contains(Integer.valueOf(suffix));
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
