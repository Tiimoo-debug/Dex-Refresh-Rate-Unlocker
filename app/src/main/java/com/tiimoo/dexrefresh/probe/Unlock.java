package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Votes;

import java.lang.reflect.Member;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * Phase 2: selectively suppress the votes that cap refresh rate.
 *
 * <h3>Off unless you turn it on</h3>
 * Nothing here runs until a command explicitly names votes to drop, and the
 * selection is cleared by a reboot. The module remains observation-only out of
 * the box.
 *
 * <h3>Why it targets (display, priority) pairs and not a rule</h3>
 * The tempting design is "drop any vote capping physical refresh below the
 * panel maximum". That would also drop thermal throttling, which exists to stop
 * the phone cooking itself, and low-power caps. On this firmware the priority
 * constants were stripped by R8, so the module cannot tell a thermal vote from
 * a DeX vote by name - and guessing wrong means silently disabling thermal
 * protection.
 *
 * <p>So the user names the exact votes, read off the probe log:
 *
 * <pre>
 *   su -c 'setprop debug.dexrr.cmd "unlock -1:19"'      drop global priority 19
 *   su -c 'setprop debug.dexrr.cmd "unlock -1:19,7:10"' and display 7's p10
 *   su -c 'setprop debug.dexrr.cmd "unlock off"'        stop
 * </pre>
 *
 * <p>Precise, reversible, and it doubles as the experiment: drop the suspected
 * vote, watch the committed specs in the next STATE line, and see whether the
 * monitor actually moves to 144.
 */
public final class Unlock {

    private Unlock() {
    }

    /** "displayId:priority" entries currently suppressed. Empty = inactive. */
    private static final Set<String> DROPPED =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    /** True while the selection is recomputed from the device on every tick. */
    private static volatile boolean autoMode;

    /**
     * Upper bound for auto mode, or 0 for "whatever the display advertises".
     *
     * <p>Exists because removing a cap is not always safe. A DisplayPort link
     * running two lanes instead of four - which is what a dock does when USB 3
     * is also carrying ethernet - has a real ceiling well below what the
     * monitor's EDID advertises. Remove the cap there and the framework can ask
     * for a mode the link cannot train, and a link that cannot train outputs
     * nothing at all rather than falling back. "auto:120" keeps caps at or
     * above 120 in place while still clearing anything lower.
     */
    private static volatile float ceiling;

    /** When the selection last changed, for the watchdog below. */
    private static volatile long lastChangeMs;
    private static volatile int displaysAtChange;
    private static volatile boolean watchdogArmed;

    /**
     * Undo the unlock if applying it made a display disappear.
     *
     * <p>Dropping a refresh-rate cap on a bandwidth-limited link can stop a
     * display coming up entirely - observed with a dock that had been working
     * at 60 Hz and went black once the caps were removed. Being left with no
     * picture and no obvious cause is a bad place to put someone, so if a
     * display vanishes within seconds of the selection changing, put the caps
     * back and say so.
     *
     * <p>Deliberately bounded to a short window after a change: a display
     * removed later is someone unplugging a cable, not this.
     */
    public static void watchdog() {
        if (!watchdogArmed) {
            return;
        }
        long sinceChange = System.currentTimeMillis() - lastChangeMs;
        if (sinceChange > WATCHDOG_WINDOW_MS) {
            watchdogArmed = false;
            return;
        }
        java.util.Set<Integer> live = Heartbeat.liveDisplayIds();
        if (live == null || live.size() >= displaysAtChange) {
            return;
        }
        watchdogArmed = false;
        ProbeLog.post("UNLOCK WATCHDOG: a display disappeared %dms after the "
                + "selection changed (%d displays -> %d). Restoring the caps.",
                sinceChange, displaysAtChange, live.size());
        ProbeLog.post("UNLOCK WATCHDOG: this usually means the link cannot carry "
                + "the mode that became reachable. Try 'auto:120', or leave the "
                + "unlock off for this connection.");
        configure("off");
    }

    private static final long WATCHDOG_WINDOW_MS = 40_000L;

    /** Note that the selection changed, so the watchdog can judge what follows. */
    private static void armWatchdog() {
        java.util.Set<Integer> live = Heartbeat.liveDisplayIds();
        displaysAtChange = live == null ? 0 : live.size();
        lastChangeMs = System.currentTimeMillis();
        watchdogArmed = displaysAtChange > 0;
    }

    public static boolean active() {
        return !DROPPED.isEmpty();
    }

    public static boolean isAuto() {
        return autoMode;
    }

    /**
     * Recompute the selection from the live vote table.
     *
     * <p>In auto mode the module asks itself the question {@code why} answers -
     * which votes cap a display below the fastest mode it advertises - and
     * drops exactly those. It re-evaluates continuously, which matters because
     * display ids churn (the HDMI screen has been 6, 7, 8 and 9 in one
     * session), because votes are re-filed when a display is re-created, and
     * because docking DeX changes which displays exist at all.
     *
     * <p>Each display is measured against its own maximum, so nothing is
     * dropped for the 120 Hz phone panel that only a 144 Hz monitor needs.
     */
    public static void refreshAuto() {
        if (!autoMode) {
            return;
        }
        java.util.TreeSet<Integer> priorities = Diagnose.blockingPriorities(ceiling);
        if (priorities.isEmpty()) {
            // Nothing capping anything, or no displays known yet. Leave the
            // current selection alone rather than flapping it off and on.
            return;
        }
        Set<String> wanted = new LinkedHashSet<String>();
        for (Integer priority : priorities) {
            // Wildcard, not a fixed id: a re-created display files the same
            // votes under a new id, and a pinned rule would quietly stop
            // working at the next mode change.
            wanted.add("*:" + priority);
        }
        if (wanted.equals(DROPPED)) {
            return;
        }
        DROPPED.clear();
        DROPPED.addAll(wanted);
        ProbeLog.post("UNLOCK auto: dropping %s%s", DROPPED,
                ceiling > 0f ? " (ceiling " + (int) ceiling + "Hz)" : "");
        armWatchdog();
        clearExisting();
    }

    public static boolean shouldDrop(int displayId, int priority) {
        if (DROPPED.isEmpty()) {
            return false;
        }
        // "*" matters because display ids are not stable: the HDMI screen was
        // observed moving 6 -> 7 -> 8 -> 9 inside a single session, since the
        // display is re-created on every mode change. A rule pinned to one id
        // would work once and then silently stop working after a redock.
        return DROPPED.contains(displayId + ":" + priority)
                || DROPPED.contains("*:" + priority);
    }

    /**
     * @param spec comma-separated "display:priority", or "off"
     */
    public static void configure(String spec) {
        if (spec == null || spec.isEmpty() || "off".equalsIgnoreCase(spec)) {
            Set<String> previous = new LinkedHashSet<String>(DROPPED);
            autoMode = false;
            ceiling = 0f;
            watchdogArmed = false;
            DROPPED.clear();
            ProbeLog.post("UNLOCK disabled (was %s). Existing votes are NOT restored "
                    + "until whatever files them files them again - reboot for a clean state.",
                    previous);
            return;
        }
        String trimmed = spec.trim();
        if (trimmed.toLowerCase(Locale.US).startsWith("auto")) {
            ceiling = 0f;
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                try {
                    ceiling = Float.parseFloat(trimmed.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    ProbeLog.post("UNLOCK ignoring unreadable ceiling in '%s'", trimmed);
                }
            }
            autoMode = true;
            DROPPED.clear();
            ProbeLog.post("UNLOCK auto mode: the capping votes are worked out from "
                    + "the device and re-checked continuously.");
            ProbeLog.post("UNLOCK note: if refresh-rate thermal throttling ever caps "
                    + "a display below its maximum, auto will drop that too. "
                    + "CPU and GPU thermal limits are untouched. Use an explicit "
                    + "list instead if that matters to you.");
            refreshAuto();
            return;
        }
        autoMode = false;
        ceiling = 0f;
        DROPPED.clear();
        for (String part : spec.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] halves = entry.split(":");
            if (halves.length != 2) {
                ProbeLog.post("UNLOCK ignoring malformed entry '%s' (want display:priority)",
                        entry);
                continue;
            }
            try {
                String displayPart = halves[0].trim();
                int priority = Integer.parseInt(halves[1].trim());
                if ("*".equals(displayPart)) {
                    DROPPED.add("*:" + priority);
                } else {
                    DROPPED.add(Integer.parseInt(displayPart) + ":" + priority);
                }
            } catch (NumberFormatException e) {
                ProbeLog.post("UNLOCK ignoring non-numeric entry '%s'", entry);
            }
        }
        if (DROPPED.isEmpty()) {
            ProbeLog.post("UNLOCK nothing valid to drop; staying inactive");
            return;
        }
        ProbeLog.post("UNLOCK active, suppressing votes %s", DROPPED);
        armWatchdog();
        ProbeLog.post("UNLOCK reminder: if one of these is a thermal or low-power "
                + "cap, you have just disabled it. Reboot clears this.");
        clearExisting();
    }

    /**
     * Remove votes that were already filed before the unlock was configured.
     *
     * <p>Suppressing future calls is not enough: the cap we care about is filed
     * once and then sits there unchanged, which is exactly why it took three
     * runs to find. It has to be actively cleared.
     *
     * <p>Uses invokeOriginalMethod so the clearing call is not itself caught by
     * our own suppression hook.
     */
    private static void clearExisting() {
        Object storage = ProbeState.votesStorage;
        if (storage == null) {
            ProbeLog.post("UNLOCK cannot clear existing votes: VotesStorage not captured yet. "
                    + "They will be suppressed as soon as they are re-filed.");
            return;
        }
        for (String entry : expandWildcards()) {
            String[] halves = entry.split(":");
            int displayId = Integer.parseInt(halves[0]);
            int priority = Integer.parseInt(halves[1]);
            try {
                Member method;
                Object[] args;
                if (displayId < 0 && ProbeState.updateGlobalVoteMethod != null) {
                    method = ProbeState.updateGlobalVoteMethod;
                    args = new Object[]{priority, null};
                } else if (ProbeState.updateVoteMethod != null) {
                    method = ProbeState.updateVoteMethod;
                    args = new Object[]{displayId, priority, null};
                } else {
                    ProbeLog.post("UNLOCK no vote method captured for %s", entry);
                    continue;
                }
                XposedBridge.invokeOriginalMethod(method, storage, args);
                ProbeLog.post("UNLOCK cleared existing vote %s", entry);
            } catch (Throwable t) {
                ProbeLog.post("UNLOCK failed to clear %s: %s", entry, t);
            }
        }
    }

    /** Turn "*:P" into one concrete entry per live display, so it can be cleared. */
    private static Set<String> expandWildcards() {
        Set<String> out = new LinkedHashSet<String>();
        for (String entry : new LinkedHashSet<String>(DROPPED)) {
            if (!entry.startsWith("*:")) {
                out.add(entry);
                continue;
            }
            String priority = entry.substring(2);
            out.add("-1:" + priority);
            for (int displayId : Votes.displayIds()) {
                out.add(displayId + ":" + priority);
            }
        }
        return out;
    }

    /**
     * Apply a selection stored in a persist.* property.
     *
     * <p>Without this the unlock has to be re-applied by hand after every
     * reboot, which makes it useless as a daily-driver setting. A persist
     * property is durable, explicit, and removed just as easily:
     *
     * <pre>
     *   su -c 'setprop persist.dexrr.unlock "-1:19,*:10,*:13"'   enable at boot
     *   su -c 'setprop persist.dexrr.unlock ""'                  stop
     * </pre>
     */
    public static void applyPersisted(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            if (active()) {
                ProbeLog.post("UNLOCK persisted selection cleared");
                configure("off");
            }
            return;
        }
        ProbeLog.post("UNLOCK applying persisted selection from %s: %s",
                com.tiimoo.dexrefresh.core.Cfg.PROP_PERSIST_UNLOCK, spec);
        configure(spec.trim());
    }

    public static String describe() {
        if (DROPPED.isEmpty()) {
            return autoMode ? "auto (nothing to drop)" : "inactive";
        }
        return String.format(Locale.US, "%sdropping %s", autoMode ? "auto " : "", DROPPED);
    }
}
