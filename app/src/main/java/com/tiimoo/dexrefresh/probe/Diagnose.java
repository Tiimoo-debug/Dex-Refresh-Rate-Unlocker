package com.tiimoo.dexrefresh.probe;

import android.util.SparseArray;

import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Votes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Why is this display not running at its maximum?"
 *
 * <p>Written after getting it wrong by eye. Having found the global 60 Hz vote
 * at priority 19, it was easy to miss that priority 11 sits in the same global
 * bucket capping physical refresh at 120 — so a recommendation to drop the
 * per-display 120 caps left the monitor at 120 anyway, because the global one
 * was still standing.
 *
 * <p>Reading a vote table by eye does not scale: votes arrive per display and
 * globally, nest inside CombinedVotes, and constrain physical and render rates
 * separately. This walks the lot and reports exactly which votes hold a display
 * below the fastest mode it advertises — including the global ones, which is
 * the part that is easy to forget.
 */
public final class Diagnose {

    private Diagnose() {
    }

    /**
     * Explain every live display.
     *
     * <p>The no-argument form exists because display ids churn - the HDMI
     * monitor has been 6, 7, 8 and 9 in one session - so requiring an id up
     * front means looking one up first and often guessing wrong.
     */
    public static void explainAll() {
        int[] ids = Votes.displayIds();
        if (ids.length == 0) {
            ProbeLog.post("no displays known yet; wait for a STATE line");
            return;
        }
        for (int id : ids) {
            explain(id);
        }
    }

    public static void explain(int displayId) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== why is display ").append(displayId)
                .append(" not at its maximum? =====\n");

        ModeInfo modes = parseModes(ProbeState.LAST_SEEN.get("modes:" + displayId));
        if (modes == null) {
            sb.append("No mode list recorded for display ").append(displayId)
                    .append(". Dock it, wait for a STATE line, then retry.\n");
            ProbeLog.postReport(sb.toString());
            return;
        }
        String name = ProbeState.LAST_SEEN.get("name:" + displayId);
        sb.append("display: ").append(name == null ? "?" : name).append('\n');
        sb.append("fastest mode advertised: ").append(modes.maxId)
                .append(" @ ").append(modes.maxRate).append(" Hz ")
                .append(sizeOf(displayId, modes.maxId)).append('\n');
        sb.append("currently active mode:   ").append(modes.activeId)
                .append(" @ ").append(modes.activeRate).append(" Hz ")
                .append(sizeOf(displayId, modes.activeId)).append('\n');
        String detail = ProbeState.LAST_SEEN.get("modedetail:" + displayId);
        if (detail != null) {
            sb.append("all modes: ").append(detail).append('\n');
        }
        String maxSize = sizeOf(displayId, modes.maxId);
        String activeSize = sizeOf(displayId, modes.activeId);
        if (!maxSize.isEmpty() && !maxSize.equals(activeSize)) {
            sb.append("NOTE: the fastest mode is a different resolution from the "
                    + "current one.\n      Unlocking the rate may drop resolution "
                    + "unless a size vote pins it.\n");
        }

        if (modes.activeRate >= modes.maxRate) {
            sb.append("\nAlready at the maximum this display advertises.\n");
            sb.append("If measured frame rate is still lower, the limit is no longer "
                    + "in the vote table - look at render rate or the app itself.\n");
            ProbeLog.postReport(sb.toString());
            return;
        }

        List<String> blockers = new ArrayList<String>();
        collectBlockers(displayId, modes.maxRate, blockers);
        collectBlockers(Votes.GLOBAL_ID, modes.maxRate, blockers);

        if (blockers.isEmpty()) {
            sb.append("\nNo vote caps this display below ").append(modes.maxRate)
                    .append(" Hz.\n")
                    .append("The limit is somewhere other than the vote table.\n");
        } else {
            sb.append("\nVotes holding it below ").append(modes.maxRate).append(" Hz:\n");
            StringBuilder spec = new StringBuilder();
            for (String b : blockers) {
                sb.append("   ").append(b).append('\n');
                int colon = b.indexOf(' ');
                if (colon > 0) {
                    if (spec.length() > 0) {
                        spec.append(',');
                    }
                    spec.append(b.substring(0, colon));
                }
            }
            sb.append("\nTo drop all of them:\n");
            sb.append("   setprop debug.dexrr.cmd \"unlock ").append(spec).append("\"\n");
            sb.append("Global entries are written as -1:<priority> and per-display ones "
                    + "as <id>:<priority>;\nprefer * over a fixed id for displays that "
                    + "get re-created, such as HDMI.\n");
        }
        ProbeLog.postReport(sb.toString());
    }

    /**
     * Priorities that cap any live display below the fastest mode it advertises.
     *
     * <p>This is the analysis {@code why} prints, returned as data so the
     * unlock can act on it directly. Computing it on the device beats a human
     * reading a vote table and relaying priorities back - which is how a global
     * 120 Hz cap got missed once already.
     *
     * <p>A display is only counted against *its own* maximum, so a vote capping
     * at 120 is a blocker for a 144 Hz monitor and not for the 120 Hz phone
     * panel. That keeps the set as small as the hardware allows.
     */
    public static java.util.TreeSet<Integer> blockingPriorities() {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<Integer>();
        for (int displayId : Votes.displayIds()) {
            ModeInfo modes = parseModes(ProbeState.LAST_SEEN.get("modes:" + displayId));
            if (modes == null || modes.maxRate <= 0f) {
                continue;
            }
            addBlockingPriorities(displayId, modes.maxRate, out);
            addBlockingPriorities(Votes.GLOBAL_ID, modes.maxRate, out);
        }
        return out;
    }

    private static void addBlockingPriorities(int displayId, float maxRate,
                                              java.util.Set<Integer> out) {
        SparseArray<?> votes = Votes.forDisplay(displayId);
        if (votes == null) {
            return;
        }
        for (int i = 0; i < votes.size(); i++) {
            if (Votes.capsBelow(votes.valueAt(i), maxRate)) {
                out.add(Integer.valueOf(votes.keyAt(i)));
            }
        }
    }

    /** Add "d:p  <rendered vote>" for each vote on this display capping below max. */
    private static void collectBlockers(int displayId, float maxRate, List<String> out) {
        SparseArray<?> votes = Votes.forDisplay(displayId);
        if (votes == null) {
            return;
        }
        for (int i = 0; i < votes.size(); i++) {
            int priority = votes.keyAt(i);
            Object vote = votes.valueAt(i);
            if (Votes.capsBelow(vote, maxRate)) {
                out.add(String.format(Locale.US, "%d:%d  %s%s",
                        displayId, priority, Votes.terse(vote),
                        displayId == Votes.GLOBAL_ID
                                ? "   <- GLOBAL, applies to every display" : ""));
            }
        }
    }

    /** Resolution of one mode id, from the recorded detail list. */
    private static String sizeOf(int displayId, int modeId) {
        String detail = ProbeState.LAST_SEEN.get("modedetail:" + displayId);
        if (detail == null) {
            return "";
        }
        for (String token : detail.trim().split("\\s+")) {
            // token form: "<id>@<rate>@<width>x<height>"
            String[] bits = token.split("@");
            if (bits.length == 3) {
                try {
                    if (Integer.parseInt(bits[0]) == modeId) {
                        return bits[2];
                    }
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return "";
    }

    private static final class ModeInfo {
        int maxId;
        float maxRate;
        int activeId;
        float activeRate;
    }

    /** Parse the recorded "86@60 87@144 88@120  active=88" form. */
    private static ModeInfo parseModes(String recorded) {
        if (recorded == null || recorded.isEmpty()) {
            return null;
        }
        ModeInfo info = new ModeInfo();
        info.activeId = -1;
        SparseArray<Float> rates = new SparseArray<Float>();
        for (String token : recorded.trim().split("\\s+")) {
            try {
                if (token.startsWith("active=")) {
                    info.activeId = Integer.parseInt(token.substring(7));
                    continue;
                }
                int at = token.indexOf('@');
                if (at <= 0) {
                    continue;
                }
                int id = Integer.parseInt(token.substring(0, at));
                float rate = Float.parseFloat(token.substring(at + 1));
                rates.put(id, rate);
                if (rate > info.maxRate) {
                    info.maxRate = rate;
                    info.maxId = id;
                }
            } catch (NumberFormatException ignored) {
                // skip unparseable token
            }
        }
        if (rates.size() == 0) {
            return null;
        }
        Float active = rates.get(info.activeId);
        info.activeRate = active == null ? 0f : active;
        return info;
    }
}
