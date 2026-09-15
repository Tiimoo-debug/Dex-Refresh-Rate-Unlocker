package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Votes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The modes each display actually advertises, as a menu you can pick from.
 *
 * <p>{@code auto} answers one question — "make it as fast as it goes" — and
 * that is not always the question. A 2560x1440 panel that will do 144 Hz at
 * 1920x1080 and only 120 at full resolution poses a real trade, and only the
 * person looking at the screen can settle it.
 *
 * <p>What this lists is the modes the framework derived from the display's
 * EDID and the link it came up on. It is not the EDID itself and nothing here
 * can add a mode to it: if a resolution and rate pair is absent, either the
 * display never advertised it or the link cannot carry it, and no vote changes
 * that.
 *
 * <p>Each line carries a stable {@code MODE} prefix so the app can parse the
 * report back into one button per mode rather than making anyone read ids out
 * of a log and retype them.
 */
public final class Modes {

    private Modes() {
    }

    /** One advertised mode. */
    public static final class Mode {
        public final int displayId;
        public final int id;
        public final int width;
        public final int height;
        public final float rate;

        Mode(int displayId, int id, int width, int height, float rate) {
            this.displayId = displayId;
            this.id = id;
            this.width = width;
            this.height = height;
            this.rate = rate;
        }

        /** "2560x1440 @ 144 Hz" */
        public String label() {
            return String.format(Locale.US, "%dx%d @ %s Hz", width, height, trim(rate));
        }
    }

    public static void report() {
        ProbeLog.postReport(describe());
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== every mode each display advertises =====\n");
        boolean any = false;
        for (int displayId : knownDisplayIds()) {
            List<Mode> modes = forDisplay(displayId);
            if (modes.isEmpty()) {
                continue;
            }
            any = true;
            String name = ProbeState.LAST_SEEN.get("name:" + displayId);
            int active = activeModeId(displayId);
            sb.append("\ndisplay ").append(displayId)
                    .append(name == null ? "" : "  (" + name + ")").append('\n');
            for (int i = 0; i < modes.size(); i++) {
                Mode mode = modes.get(i);
                // Stable prefix: the app parses these lines back into buttons.
                sb.append(String.format(Locale.US, "  MODE d%d id=%-4d %9s @ %7s Hz%s%n",
                        mode.displayId, mode.id, mode.width + "x" + mode.height,
                        trim(mode.rate), mode.id == active ? "   <- active now" : ""));
            }
        }
        if (!any) {
            sb.append("\nNo mode lists recorded yet. Plug a display in, wait for a\n")
                    .append("STATE line, and try again.\n");
            return sb.toString();
        }
        sb.append("\nTo hold a display at one of them:\n")
                .append("   setprop debug.dexrr.cmd \"pin <display> <id>\"\n")
                .append("for example  pin 9 87.  Undo with  pin off.\n")
                .append("A pin that asks for a mode below the current cap needs no\n")
                .append("unlock; one above it needs the caps dropped too.\n");
        return sb.toString();
    }

    /**
     * Displays we have a mode list for.
     *
     * <p>Deliberately not {@code Votes.displayIds()}: that enumerates the vote
     * map, and a display can have a recorded mode list without carrying a vote
     * of its own - in which case the menu would silently omit the very display
     * being asked about. The recorded lists are the authority here, with the
     * vote map's ids folded in so a display that has votes but no list still
     * shows up as empty rather than vanishing.
     */
    private static java.util.TreeSet<Integer> knownDisplayIds() {
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<Integer>();
        for (String key : ProbeState.LAST_SEEN.keySet()) {
            if (!key.startsWith("modedetail:")) {
                continue;
            }
            try {
                ids.add(Integer.valueOf(key.substring("modedetail:".length())));
            } catch (NumberFormatException ignored) {
                // not a display id; skip
            }
        }
        for (int id : Votes.displayIds()) {
            ids.add(Integer.valueOf(id));
        }
        return ids;
    }

    /**
     * Every advertised mode of one display, fastest first, then widest.
     *
     * <p>Read from the recorded mode detail rather than from the live display,
     * so it costs no binder call and is safe from any thread.
     */
    public static List<Mode> forDisplay(int displayId) {
        List<Mode> out = new ArrayList<Mode>();
        String detail = ProbeState.LAST_SEEN.get("modedetail:" + displayId);
        if (detail == null || detail.isEmpty()) {
            return out;
        }
        for (String token : detail.trim().split("\\s+")) {
            // token form: "<id>@<rate>@<width>x<height>"
            String[] bits = token.split("@");
            if (bits.length != 3) {
                continue;
            }
            int cross = bits[2].indexOf('x');
            if (cross <= 0) {
                continue;
            }
            try {
                out.add(new Mode(displayId,
                        Integer.parseInt(bits[0]),
                        Integer.parseInt(bits[2].substring(0, cross)),
                        Integer.parseInt(bits[2].substring(cross + 1)),
                        Float.parseFloat(bits[1])));
            } catch (NumberFormatException ignored) {
                // a malformed token is not worth failing the whole list over
            }
        }
        Collections.sort(out, new Comparator<Mode>() {
            @Override
            public int compare(Mode a, Mode b) {
                if (a.rate != b.rate) {
                    return Float.compare(b.rate, a.rate);
                }
                if (a.width != b.width) {
                    return b.width - a.width;
                }
                return a.id - b.id;
            }
        });
        return out;
    }

    /** One mode by id, or null. */
    public static Mode find(int displayId, int modeId) {
        for (Mode mode : forDisplay(displayId)) {
            if (mode.id == modeId) {
                return mode;
            }
        }
        return null;
    }

    /** The mode id the display is running now, or -1. */
    public static int activeModeId(int displayId) {
        String recorded = ProbeState.LAST_SEEN.get("modes:" + displayId);
        if (recorded == null) {
            return -1;
        }
        for (String token : recorded.trim().split("\\s+")) {
            if (token.startsWith("active=")) {
                try {
                    return Integer.parseInt(token.substring("active=".length()));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** 144.0 -> "144", 59.997 -> "59.997". */
    static String trim(float rate) {
        return rate == Math.rint(rate)
                ? String.valueOf((long) rate)
                : String.valueOf(rate);
    }
}
