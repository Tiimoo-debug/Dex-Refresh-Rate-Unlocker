package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.ProbeLog;

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

    public static boolean active() {
        return !DROPPED.isEmpty();
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
            DROPPED.clear();
            ProbeLog.post("UNLOCK disabled (was %s). Existing votes are NOT restored "
                    + "until whatever files them files them again - reboot for a clean state.",
                    previous);
            return;
        }
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
            for (int displayId : Snapshots.knownDisplayIds()) {
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
            return;
        }
        ProbeLog.post("UNLOCK applying persisted selection from %s: %s",
                com.tiimoo.dexrefresh.core.Cfg.PROP_PERSIST_UNLOCK, spec);
        configure(spec.trim());
    }

    public static String describe() {
        return DROPPED.isEmpty() ? "inactive"
                : String.format(Locale.US, "dropping %s", DROPPED);
    }
}
