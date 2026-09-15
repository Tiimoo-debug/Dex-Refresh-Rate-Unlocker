package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * Hold a display at one exact mode.
 *
 * <p>The rest of this module only ever takes votes away. This is the one place
 * that files one, because "run at exactly 1920x1080 at 144" cannot be
 * expressed by removing anything — the framework picks the best mode that
 * every surviving vote permits, and "best" is its opinion, not the user's.
 *
 * <h3>How a mode gets pinned</h3>
 * {@code Vote.forSupportedModes(List<Integer>)} narrows the set of mode ids the
 * summary will consider, by {@code retainAll}. A vote naming exactly one id
 * therefore leaves the framework one choice. A base-mode vote is filed with it
 * so the chosen mode is also the base one, both wrapped in a single
 * {@code CombinedVote} so they occupy one priority slot instead of two.
 *
 * <h3>Why priority 20</h3>
 * Votes are applied from {@code MAX_PRIORITY} downward, and when the surviving
 * set comes out empty the director retries having dropped the lowest
 * priorities first. A pin filed low would be the first thing discarded in
 * exactly the case where it matters. 20 is {@code MAX_PRIORITY} on Android 15,
 * where AOSP uses it for UDFPS — which concerns the built-in panel only, so on
 * an external display the slot is free in practice. Pinning display 0 does
 * overwrite it, and says so.
 *
 * <p>A pin narrows; it cannot widen. Asking for a mode faster than a surviving
 * cap allows leaves the cap in charge, so a pin above the current ceiling
 * needs {@code unlock} as well. The report says when that is the case.
 */
public final class Pin {

    private Pin() {
    }

    /** MAX_PRIORITY on Android 15. Rejected outright by the framework if wrong. */
    public static final int PRIORITY = 20;

    private static final String VOTE = "com.android.server.display.mode.Vote";
    private static final String COMBINED_VOTE = "com.android.server.display.mode.CombinedVote";

    /** Display ids we currently hold a pin on. */
    private static final List<Integer> PINNED =
            Collections.synchronizedList(new ArrayList<Integer>());

    private static volatile String description = "";

    public static boolean active() {
        return !PINNED.isEmpty();
    }

    public static String describe() {
        return description;
    }

    /** True for a vote slot this module filed, which nothing else may drop. */
    public static boolean isOurs(int displayId, int priority) {
        return priority == PRIORITY && PINNED.contains(Integer.valueOf(displayId));
    }

    /**
     * Apply a spec: {@code "<displayId> <modeId>"}, or {@code "off"}.
     */
    public static void configure(String spec) {
        if (spec == null || spec.trim().isEmpty() || "off".equalsIgnoreCase(spec.trim())) {
            clear();
            return;
        }
        String[] parts = spec.trim().split("[\\s:]+");
        if (parts.length < 2) {
            ProbeLog.postReport("\npin: need a display and a mode id, as in "
                    + "\"pin 9 87\". Run \"modes\" for the list.\n");
            return;
        }
        int displayId;
        int modeId;
        try {
            displayId = Integer.parseInt(parts[0]);
            modeId = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            ProbeLog.postReport("\npin: '" + spec + "' is not \"<display> <mode>\". "
                    + "Run \"modes\" for the list.\n");
            return;
        }
        apply(displayId, modeId);
    }

    public static void apply(int displayId, int modeId) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== pin display ").append(displayId)
                .append(" to mode ").append(modeId).append(" =====\n");

        Modes.Mode mode = Modes.find(displayId, modeId);
        if (mode == null) {
            sb.append("Display ").append(displayId)
                    .append(" does not advertise mode ").append(modeId).append(".\n")
                    .append("Run \"modes\" for what it does advertise. Nothing here can\n")
                    .append("add a mode the display or the link did not offer.\n");
            ProbeLog.postReport(sb.toString());
            return;
        }
        sb.append("mode ").append(modeId).append(" is ").append(mode.label()).append('\n');

        Object storage = ProbeState.votesStorage;
        if (storage == null || ProbeState.updateVoteMethod == null) {
            sb.append("\nVotesStorage was not captured, so no vote can be filed.\n")
                    .append("Reboot with the module enabled and try again.\n");
            ProbeLog.postReport(sb.toString());
            return;
        }

        Object vote = buildVote(modeId, mode.rate, sb);
        if (vote == null) {
            ProbeLog.postReport(sb.toString());
            return;
        }

        if (displayId == 0) {
            sb.append("\nNOTE: display 0 is the built-in panel, where priority ")
                    .append(PRIORITY).append(" is the\n")
                    .append("fingerprint reader's slot on stock Android. Pinning it here\n")
                    .append("overwrites that vote until you run \"pin off\".\n");
        }

        try {
            XposedBridge.invokeOriginalMethod(ProbeState.updateVoteMethod, storage,
                    new Object[]{displayId, PRIORITY, vote});
        } catch (Throwable t) {
            sb.append("\nfiling the vote failed: ").append(t).append('\n');
            ProbeLog.postReport(sb.toString());
            return;
        }

        if (!PINNED.contains(Integer.valueOf(displayId))) {
            PINNED.add(Integer.valueOf(displayId));
        }
        description = "d" + displayId + " mode " + modeId + " (" + mode.label() + ")";
        persist(displayId + " " + modeId);

        sb.append("\nfiled at priority ").append(PRIORITY).append(".\n");
        appendReachability(sb, displayId, mode);
        sb.append("\nUndo with:  setprop debug.dexrr.cmd \"pin off\"\n");
        ProbeLog.postReport(sb.toString());
    }

    /**
     * Say whether this pin can actually take effect, before the user waits to
     * find out. A pin narrows and never widens, so a cap above it still wins.
     */
    private static void appendReachability(StringBuilder sb, int displayId, Modes.Mode mode) {
        java.util.TreeSet<Integer> blocking = Diagnose.blockingPriorities(0f);
        blocking.remove(Integer.valueOf(PRIORITY));
        if (blocking.isEmpty()) {
            sb.append("Nothing else is capping this display, so the pin decides it.\n");
            return;
        }
        // A cap only matters if it sits below the rate being asked for.
        sb.append("Other votes are still capping this display (priorities ")
                .append(blocking).append(").\n")
                .append("A pin narrows and never widens, so if any of them sits below ")
                .append(Modes.trim(mode.rate)).append(" Hz\n")
                .append("the cap wins and this pin does nothing. Run \"why ")
                .append(displayId).append("\" to see them, or\n")
                .append("\"unlock auto\" to drop them first.\n");
    }

    /**
     * A single vote pinning both the mode set and the base rate.
     *
     * <p>Falls back to the mode-set vote alone when {@code CombinedVote} cannot
     * be constructed, which is the part most likely to differ on a firmware
     * that has been through R8.
     */
    private static Object buildVote(int modeId, float rate, StringBuilder sb) {
        Class<?> voteClass = Reflect.cls(ProbeState.systemServerClassLoader, VOTE);
        if (voteClass == null) {
            sb.append("\nVote class not found; cannot build a vote.\n");
            return null;
        }
        Object modeSet = callFactory(voteClass, "forSupportedModes",
                new Class<?>[]{List.class},
                new Object[]{Collections.singletonList(Integer.valueOf(modeId))});
        if (modeSet == null) {
            sb.append("\nVote.forSupportedModes is absent on this firmware, so a mode\n")
                    .append("cannot be pinned by id here.\n");
            return null;
        }
        Object baseMode = callFactory(voteClass, "forBaseModeRefreshRate",
                new Class<?>[]{float.class}, new Object[]{Float.valueOf(rate)});
        if (baseMode == null) {
            sb.append("(base-mode vote unavailable; pinning the mode set alone)\n");
            return modeSet;
        }
        Object combined = combine(modeSet, baseMode);
        if (combined == null) {
            sb.append("(CombinedVote unavailable; pinning the mode set alone)\n");
            return modeSet;
        }
        return combined;
    }

    private static Object callFactory(Class<?> owner, String name,
                                      Class<?>[] types, Object[] args) {
        try {
            Method m = owner.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object combine(Object first, Object second) {
        try {
            Class<?> c = Reflect.cls(ProbeState.systemServerClassLoader, COMBINED_VOTE);
            if (c == null) {
                return null;
            }
            java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            List<Object> both = new ArrayList<Object>(2);
            both.add(first);
            both.add(second);
            return ctor.newInstance(both);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Remove every pin this module filed. */
    public static void clear() {
        Object storage = ProbeState.votesStorage;
        Member method = ProbeState.updateVoteMethod;
        Integer[] displays;
        synchronized (PINNED) {
            displays = PINNED.toArray(new Integer[0]);
        }
        if (displays.length == 0) {
            ProbeLog.post("PIN nothing pinned");
            persist("");
            return;
        }
        for (Integer displayId : displays) {
            if (storage == null || method == null) {
                break;
            }
            try {
                XposedBridge.invokeOriginalMethod(method, storage,
                        new Object[]{displayId, PRIORITY, null});
                ProbeLog.post("PIN cleared d%d priority %d", displayId, PRIORITY);
            } catch (Throwable t) {
                ProbeLog.post("PIN failed to clear d%d: %s", displayId, t);
            }
        }
        PINNED.clear();
        description = "";
        persist("");
        ProbeLog.post("PIN off. Whatever the framework files at priority %d next "
                + "takes the slot back.", PRIORITY);
    }

    /** Re-apply a pin saved across a reboot. */
    public static void applyPersisted(String spec) {
        if (spec != null && !spec.trim().isEmpty()) {
            ProbeLog.post("PIN restoring persisted pin '%s'", spec);
            configure(spec);
        }
    }

    private static void persist(String value) {
        try {
            Method set = Class.forName("android.os.SystemProperties")
                    .getMethod("set", String.class, String.class);
            set.invoke(null, Cfg.PROP_PERSIST_PIN, value);
        } catch (Throwable t) {
            ProbeLog.post("PIN could not persist '%s': %s", value, t);
        }
    }
}
