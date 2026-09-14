package com.tiimoo.dexrefresh.probe;

import android.util.SparseArray;

import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Votes;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Two captures of the vote table, and what changed between them.
 *
 * <p>Every conclusion in this project so far came from reading two snapshots
 * side by side and spotting the difference by eye. That is how a global 120 Hz
 * cap got missed in a table of a dozen votes, and a full snapshot runs to
 * hundreds of lines. The device can do the comparison exactly.
 *
 * <p>What makes this worth having now is that several things on this phone
 * change the refresh rate and none of them have been told apart: Samsung's own
 * policy, DispUnlock, MultiStar's higher-resolutions toggle, and this module.
 * Each has a switch. Capture, flip one switch, capture again, and the diff
 * names precisely what that switch does — no reboot, no shell, no reading two
 * walls of text in parallel.
 *
 * <p>Captures are field reads off the live vote map, same as everything else
 * here, and are stored as flat strings so nothing in a capture keeps a
 * framework object alive.
 */
public final class Compare {

    private Compare() {
    }

    /** Captures worth keeping. Older ones fall off the front. */
    private static final int MAX_CAPTURES = 8;

    /** label -> ("d9 p10" -> "PhysicalVote(0,120)"), insertion-ordered. */
    private static final LinkedHashMap<String, Map<String, String>> CAPTURES =
            new LinkedHashMap<String, Map<String, String>>();

    /**
     * Record the current vote table and display state under a label.
     *
     * <p>Re-using a label replaces the earlier capture, so re-running a leg of
     * an experiment does the obvious thing.
     */
    public static void capture(String label) {
        ProbeLog.post("%s", record(label));
    }

    /** Record a capture and return a one-line summary of what was taken. */
    public static synchronized String record(String label) {
        Map<String, String> state = new LinkedHashMap<String, String>();
        SparseArray<?> outer = Votes.byDisplay();
        if (outer != null) {
            for (int i = 0; i < outer.size(); i++) {
                int displayId = outer.keyAt(i);
                Object inner = outer.valueAt(i);
                if (!(inner instanceof SparseArray)) {
                    continue;
                }
                SparseArray<?> votes = (SparseArray<?>) inner;
                for (int j = 0; j < votes.size(); j++) {
                    state.put(String.format(Locale.US, "vote d%d p%d",
                                    displayId, votes.keyAt(j)),
                            Votes.terse(votes.valueAt(j)));
                }
            }
        }
        // The observed outcome alongside the votes, so a capture shows both
        // what was asked for and what the display actually did.
        for (Map.Entry<String, String> entry : ProbeState.LAST_SEEN.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("modes:") || key.startsWith("render:")
                    || key.startsWith("committed:") || key.startsWith("override:")
                    || key.startsWith("refreshRateMode:")) {
                state.put(key, entry.getValue());
            }
        }
        CAPTURES.remove(label);
        CAPTURES.put(label, state);
        while (CAPTURES.size() > MAX_CAPTURES) {
            CAPTURES.remove(CAPTURES.keySet().iterator().next());
        }
        return String.format(Locale.US, "captured '%s': %d entries (held: %s)",
                label, state.size(), labels());
    }

    /** Comma-separated labels currently held, oldest first. */
    public static synchronized String labels() {
        if (CAPTURES.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (String label : CAPTURES.keySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(label);
        }
        return sb.toString();
    }

    /**
     * Report what changed between two captures.
     *
     * <p>With no arguments, the two most recent — which is the common case,
     * since an A/B is usually captured back to back.
     */
    public static void diff(String from, String to) {
        ProbeLog.postReport(report(from, to));
    }

    /**
     * The comparison as text.
     *
     * <p>Separate from {@link #diff} so the result can be examined without a
     * log to read it out of, which is what makes this testable off-device.
     */
    public static synchronized String report(String from, String to) {
        if (from == null || to == null) {
            String[] recent = twoMostRecent();
            if (recent == null) {
                return "\nNeed two captures to compare. Take one, change "
                        + "one thing, take another:\n"
                        + "   setprop debug.dexrr.cmd \"capture before\"\n"
                        + "   ... flip the switch ...\n"
                        + "   setprop debug.dexrr.cmd \"capture after\"\n"
                        + "   setprop debug.dexrr.cmd \"diff\"\n"
                        + "Held right now: " + labels() + "\n";
            }
            from = recent[0];
            to = recent[1];
        }
        Map<String, String> before = CAPTURES.get(from);
        Map<String, String> after = CAPTURES.get(to);
        if (before == null || after == null) {
            return "\nNo capture named '"
                    + (before == null ? from : to) + "'. Held: " + labels() + "\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n===== '").append(from).append("' -> '").append(to)
                .append("' =====\n");

        TreeSet<String> keys = new TreeSet<String>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        int changes = 0;
        for (String key : keys) {
            String was = before.get(key);
            String now = after.get(key);
            if (was == null) {
                sb.append("  + ").append(key).append("  ").append(now).append('\n');
                changes++;
            } else if (now == null) {
                sb.append("  - ").append(key).append("  ").append(was).append('\n');
                changes++;
            } else if (!was.equals(now)) {
                sb.append("  ~ ").append(key).append("  ").append(was)
                        .append("  ->  ").append(now).append('\n');
                changes++;
            }
        }

        if (changes == 0) {
            sb.append("  nothing changed. Whatever was flipped does not reach the\n")
                    .append("  vote table, so it works somewhere else entirely.\n");
        } else {
            sb.append("\n  ").append(changes).append(" change(s). ")
                    .append("'+' appeared, '-' went away, '~' was rewritten.\n")
                    .append("  A vote that appears or tightens between the two is what\n")
                    .append("  that switch does.\n");
        }
        return sb.toString();
    }

    /** The last two labels added, oldest of the pair first, or null. */
    private static String[] twoMostRecent() {
        if (CAPTURES.size() < 2) {
            return null;
        }
        String[] all = CAPTURES.keySet().toArray(new String[0]);
        return new String[] {all[all.length - 2], all[all.length - 1]};
    }
}
