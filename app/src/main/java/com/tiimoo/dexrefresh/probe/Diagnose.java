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

        // Two rates, reported separately. The panel can scan at 144 Hz while
        // content is produced at 60 - which is what "the monitor reports 144
        // but it feels like 60" actually is. Reporting only the mode, as this
        // did, hides exactly that case.
        String renderText = ProbeState.LAST_SEEN.get("render:" + displayId);
        float renderRate = parseRate(renderText);
        sb.append("panel scans at:      ").append(modes.activeRate).append(" Hz\n");
        sb.append("content produced at: ")
                .append(renderText == null ? "unknown" : renderText).append('\n');
        String override = ProbeState.LAST_SEEN.get("override:" + displayId);
        if (override != null) {
            sb.append("frame rate override: ").append(override).append('\n');
        }

        List<String> spec = new ArrayList<String>();
        boolean looked = false;
        boolean anything = false;

        if (modes.activeRate < modes.maxRate) {
            looked = true;
            anything |= report(sb, spec, "panel scan rate", displayId, modes.maxRate, true);
        } else {
            sb.append("\nThe panel already scans at the fastest mode it advertises.\n");
        }

        // A hair of slack: render rates land on 59.997 and friends, and a
        // fraction of a hertz is not a cap worth chasing.
        if (renderRate > 0f && renderRate < modes.maxRate - 1f) {
            looked = true;
            anything |= report(sb, spec, "render frame rate", displayId, modes.maxRate, false);
        }

        if (!looked) {
            sb.append("Content is produced as fast as the panel scans. Nothing to unlock.\n");
        } else if (!anything) {
            sb.append("\nNo vote is holding this display back. Any limit is outside the\n")
                    .append("vote table - the link, the compositor, or the app itself.\n");
        } else {
            sb.append("\nTo drop all of them:\n");
            sb.append("   setprop debug.dexrr.cmd \"unlock ").append(join(spec)).append("\"\n");
            sb.append("Global entries are -1:<priority>; prefer * over a fixed id for\n")
                    .append("displays that get re-created, such as HDMI.\n");
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
    public static java.util.TreeSet<Integer> blockingPriorities(float ceiling) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<Integer>();
        for (int displayId : Votes.displayIds()) {
            ModeInfo modes = parseModes(ProbeState.LAST_SEEN.get("modes:" + displayId));
            if (modes == null || modes.maxRate <= 0f) {
                continue;
            }
            // A ceiling keeps caps at or above it in place, so a cap that
            // matches what a bandwidth-limited link can actually carry is not
            // removed along with the ones that are merely policy.
            float target = ceiling > 0f ? Math.min(ceiling, modes.maxRate) : modes.maxRate;
            addBlockingPriorities(displayId, target, out);
            addBlockingPriorities(Votes.GLOBAL_ID, target, out);
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

    /**
     * Report the votes limiting one rate, and add them to the unlock spec.
     *
     * <p>Physical and render votes are reported apart because they are apart in
     * the framework: a {@code PhysicalVote} sets how fast the panel scans, a
     * {@code RenderVote} how fast content is produced for it. Lumping them
     * together is what made a 144 Hz panel showing 60 fps content look like a
     * single unexplained number.
     *
     * @return true if anything was found holding this rate down
     */
    private static boolean report(StringBuilder sb, List<String> spec, String label,
                                  int displayId, float target, boolean physical) {
        List<String> blockers = new ArrayList<String>();
        collectTyped(displayId, target, physical, blockers);
        collectTyped(Votes.GLOBAL_ID, target, physical, blockers);
        sb.append("\nvotes limiting the ").append(label).append(":\n");
        if (blockers.isEmpty()) {
            sb.append("   (none - the limit is not in the vote table)\n");
            return false;
        }
        for (int i = 0; i < blockers.size(); i++) {
            sb.append("   ").append(blockers.get(i)).append('\n');
            // Both rates are often held by the same vote; list each once.
            String key = blockers.get(i).split("\\s+")[0];
            if (!spec.contains(key)) {
                spec.add(key);
            }
        }
        return true;
    }

    /** Add "d:p  <rendered vote>" for each vote of one kind capping below target. */
    private static void collectTyped(int displayId, float target, boolean physical,
                                     List<String> out) {
        SparseArray<?> votes = Votes.forDisplay(displayId);
        if (votes == null) {
            return;
        }
        for (int i = 0; i < votes.size(); i++) {
            Object vote = votes.valueAt(i);
            boolean caps = physical
                    ? Votes.capsPhysicalBelow(vote, target)
                    : Votes.capsRenderBelow(vote, target);
            if (caps) {
                out.add(String.format(Locale.US, "%d:%d  %s%s",
                        displayId, votes.keyAt(i), Votes.terse(vote),
                        displayId == Votes.GLOBAL_ID
                                ? "   <- GLOBAL, applies to every display" : ""));
            }
        }
    }

    /** Comma-separated spec, as {@code unlock} expects it. */
    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Leading number of a recorded rate such as "60.0 Hz"; 0 if unparseable. */
    private static float parseRate(String text) {
        if (text == null) {
            return 0f;
        }
        String trimmed = text.trim();
        int end = 0;
        while (end < trimmed.length()
                && (Character.isDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '.')) {
            end++;
        }
        if (end == 0) {
            return 0f;
        }
        try {
            return Float.parseFloat(trimmed.substring(0, end));
        } catch (NumberFormatException e) {
            return 0f;
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
