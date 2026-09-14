package com.tiimoo.dexrefresh.core;

import android.util.SparseArray;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Everything the module knows about reading Android 15's refresh-rate votes.
 *
 * <p>This used to live in four places — the heartbeat, the diagnosis, the
 * snapshot and the hooks each walked the vote map and unwrapped nested votes
 * their own way. Four copies of the same reflective traversal is four chances
 * to get it subtly different, and the bug that cost the most time so far was
 * exactly that kind: a {@code CombinedVote} rendered as its wrapper name,
 * hiding the cap inside it.
 *
 * <h3>The model, as observed on One UI 7 / Android 15</h3>
 * {@code DisplayModeDirector} holds a {@code VotesStorage} holding a
 * {@code SparseArray<SparseArray<Vote>>} keyed by display id then priority.
 * Display id {@code -1} is the global bucket and merges into every display.
 * A {@code Vote} is an interface; each kind is its own class, and
 * {@code CombinedVote} wraps a {@code List<Vote>} applied in turn. The
 * effective ceiling for a display is the minimum of every vote's maximum, so
 * any single vote can cap it.
 *
 * <p>Nothing here calls a method on a vote or takes a lock: field reads only,
 * every one wrapped. It is safe from a hook callback and from our own threads.
 */
public final class Votes {

    private Votes() {
    }

    /** The pseudo-display global votes are filed under. */
    public static final int GLOBAL_ID = -1;

    /** Recursion guard for nested votes; the real nesting is one deep. */
    private static final int MAX_NESTING = 3;

    /** Set by the hooks once the mode director instance is known. */
    private static volatile Object modeDirector;

    public static void setModeDirector(Object director) {
        modeDirector = director;
    }

    public static Object modeDirector() {
        return modeDirector;
    }

    /** display id -> priority -> vote, or null if not resolvable. */
    public static SparseArray<?> byDisplay() {
        try {
            Object director = modeDirector;
            if (director == null) {
                return null;
            }
            Object storage = Reflect.findByTypeFragment(director, "VotesStorage");
            Object map = storage != null
                    ? Reflect.findByTypeFragment(storage, "SparseArray")
                    // Pre-Android-14 layouts keep the map on the director itself.
                    : Reflect.findByTypeFragment(director, "SparseArray");
            return map instanceof SparseArray ? (SparseArray<?>) map : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** priority -> vote for one display, or null. */
    public static SparseArray<?> forDisplay(int displayId) {
        SparseArray<?> outer = byDisplay();
        if (outer == null) {
            return null;
        }
        Object inner = outer.get(displayId);
        return inner instanceof SparseArray ? (SparseArray<?>) inner : null;
    }

    /** Real display ids present in the vote map, global excluded, 0 always included. */
    public static int[] displayIds() {
        TreeSet<Integer> ids = new TreeSet<Integer>();
        ids.add(Integer.valueOf(0));
        SparseArray<?> outer = byDisplay();
        if (outer != null) {
            for (int i = 0; i < outer.size(); i++) {
                int id = outer.keyAt(i);
                if (id >= 0) {
                    ids.add(Integer.valueOf(id));
                }
            }
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) {
            out[i++] = id.intValue();
        }
        return out;
    }

    /**
     * The votes nested inside a CombinedVote, or null if this is a leaf.
     *
     * <p>Found by type rather than by field name, since the name is gone on
     * this firmware — R8 stripped it along with the priority constants.
     *
     * <p>"A list field" is not enough on its own: {@code SupportedModesVote}
     * holds a {@code List<Integer>} of mode ids and
     * {@code SupportedRefreshRatesVote} a list of rate pairs, and treating
     * either as nested votes rendered them as {@code [Integer Integer]} and
     * hid what they actually pin. So the list only counts if its contents are
     * votes — tested by asking whether an element satisfies one of the
     * interfaces this vote itself implements, which is true of CombinedVote's
     * children and of nothing else, without naming a class R8 may have
     * renamed.
     */
    public static List<?> nested(Object vote) {
        if (vote == null) {
            return null;
        }
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
                    Object value = f.get(vote);
                    if (value instanceof List && !((List<?>) value).isEmpty()
                            && holdsVotes(vote, ((List<?>) value).get(0))) {
                        return (List<?>) value;
                    }
                } catch (Throwable ignored) {
                    // unreadable; keep looking
                }
            }
        }
        return null;
    }

    /** True when {@code element} is a vote of the same family as {@code vote}. */
    private static boolean holdsVotes(Object vote, Object element) {
        if (element == null) {
            return false;
        }
        for (Class<?> k = vote.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Class<?> iface : k.getInterfaces()) {
                if (iface.isInstance(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when this vote, or one nested in it, caps any rate below {@code limit}. */
    public static boolean capsBelow(Object vote, float limit) {
        return capsBelow(vote, limit, null, 0);
    }

    /**
     * Only votes on the rate the panel scans at.
     *
     * <p>Android votes on two different things and they are easy to conflate:
     * the <em>physical</em> refresh rate is how fast the panel scans, the
     * <em>render</em> frame rate is how fast content is produced into it. A
     * display can scan at 144 Hz while showing 60 fps, which looks like the
     * monitor lying about its refresh rate and is not.
     */
    public static boolean capsPhysicalBelow(Object vote, float limit) {
        return capsBelow(vote, limit, "Physical", 0);
    }

    /** Only votes on the rate content is produced at. */
    public static boolean capsRenderBelow(Object vote, float limit) {
        return capsBelow(vote, limit, "Render", 0);
    }

    private static boolean capsBelow(Object vote, float limit, String kind, int depth) {
        if (vote == null || depth > MAX_NESTING) {
            return false;
        }
        try {
            List<?> children = nested(vote);
            if (children != null) {
                for (Object child : children) {
                    if (capsBelow(child, limit, kind, depth + 1)) {
                        return true;
                    }
                }
                return false;
            }
            // A vote can also cap by restricting which rates are allowed at
            // all, rather than by naming a maximum. Its highest entry is the
            // ceiling it imposes. This constrains both the scan-out rate and
            // the render rate, so it counts under either kind.
            float allowed = highestAllowedRate(vote);
            if (allowed > 0f) {
                return allowed < limit;
            }
            if (kind != null && !vote.getClass().getName().contains(kind)) {
                return false;
            }
            Object max = Reflect.get(vote, "mMaxRefreshRate");
            if (!(max instanceof Number)) {
                return false;
            }
            float value = ((Number) max).floatValue();
            // Zero means "no vote", and infinity is a floor-only vote such as
            // RenderVote(120, inf), which constrains nothing above.
            return value > 0f && value < limit;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A vote rendered short: kind plus whatever it constrains.
     *
     * <p>CombinedVote expands to its contents. Printing only the wrapper is
     * what hid the 60 Hz global cap for three runs.
     */
    public static String terse(Object vote) {
        return terse(vote, 0);
    }

    private static String terse(Object vote, int depth) {
        if (vote == null) {
            return "null";
        }
        try {
            String kind = simpleKind(vote.getClass());
            if (depth < MAX_NESTING) {
                List<?> children = nested(vote);
                if (children != null) {
                    StringBuilder sb = new StringBuilder(kind).append('[');
                    for (int i = 0; i < children.size(); i++) {
                        if (i > 0) {
                            sb.append(' ');
                        }
                        sb.append(terse(children.get(i), depth + 1));
                    }
                    return sb.append(']').toString();
                }
            }
            Object min = Reflect.get(vote, "mMinRefreshRate");
            Object max = Reflect.get(vote, "mMaxRefreshRate");
            if (min != null || max != null) {
                return kind + "(" + rate(min) + "," + rate(max) + ")";
            }
            Object width = Reflect.get(vote, "mWidth");
            Object height = Reflect.get(vote, "mHeight");
            if (width != null && height != null) {
                return kind + "(" + width + "x" + height + ")";
            }
            // The ids themselves, not "[size=2]": which modes a vote allows is
            // the whole content of the vote, and a count says nothing.
            List<?> modeIds = pinnedModeIds(vote);
            if (modeIds != null) {
                StringBuilder sb = new StringBuilder(kind).append("(modes");
                for (Object id : modeIds) {
                    sb.append(' ').append(id);
                }
                return sb.append(')').toString();
            }
            List<?> allowed = allowedRates(vote);
            if (allowed != null) {
                StringBuilder sb = new StringBuilder(kind).append("(peaks");
                for (Object entry : allowed) {
                    sb.append(' ').append(rate(Reflect.get(entry, "mPeakRefreshRate")));
                }
                return sb.append(')').toString();
            }
            // The base-mode rate is filed under two different names depending
            // on the vote; neither is "mBaseModeRefreshRate", which is what
            // this looked for and never found.
            Object base = Reflect.get(vote, "mAppRequestBaseModeRefreshRate");
            if (base == null) {
                base = Reflect.get(vote, "mRefreshRate");
            }
            if (base instanceof Number) {
                return kind + "(" + rate(base) + ")";
            }
            Object disable = Reflect.get(vote, "mDisableRefreshRateSwitching");
            if (disable != null) {
                return kind + "(" + disable + ")";
            }
            return kind;
        } catch (Throwable t) {
            return "<unreadable vote>";
        }
    }

    /**
     * The rate-pair list of a vote that whitelists rates, or null.
     *
     * <p>{@code SupportedRefreshRatesVote} holds {@code RefreshRates(peak,
     * vsync)} entries. Identified by the shape of its contents rather than by
     * class name, so R8 renaming does not hide it.
     */
    private static List<?> allowedRates(Object vote) {
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
                    Object value = f.get(vote);
                    if (value instanceof List && !((List<?>) value).isEmpty()
                            && Reflect.get(((List<?>) value).get(0), "mPeakRefreshRate") != null) {
                        return (List<?>) value;
                    }
                } catch (Throwable ignored) {
                    // unreadable; keep looking
                }
            }
        }
        return null;
    }

    /** Highest rate a whitelisting vote permits, or 0 if it is not one. */
    public static float highestAllowedRate(Object vote) {
        List<?> allowed = allowedRates(vote);
        if (allowed == null) {
            return 0f;
        }
        float highest = 0f;
        for (Object entry : allowed) {
            Object peak = Reflect.get(entry, "mPeakRefreshRate");
            if (peak instanceof Number) {
                highest = Math.max(highest, ((Number) peak).floatValue());
            }
        }
        return highest;
    }

    /**
     * The mode ids a vote pins the display to, or null if it pins none.
     *
     * <p>A vote naming a set of modes caps the rate just as surely as one
     * naming a maximum, but says nothing about rates itself — resolving it
     * needs the display's mode list, which is why the caller does that part.
     */
    public static List<?> pinnedModeIds(Object vote) {
        if (vote == null) {
            return null;
        }
        Object ids = Reflect.get(vote, "mModeIds");
        return ids instanceof List && !((List<?>) ids).isEmpty() ? (List<?>) ids : null;
    }

    /** "RefreshRateVote$RenderVote" -> "RenderVote". */
    private static String simpleKind(Class<?> c) {
        String name = Reflect.simple(c);
        int dollar = name.lastIndexOf('$');
        return dollar < 0 ? name : name.substring(dollar + 1);
    }

    private static String rate(Object value) {
        if (!(value instanceof Number)) {
            return String.valueOf(value);
        }
        double d = ((Number) value).doubleValue();
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Priorities present for a display, ascending. Never null. */
    public static List<Integer> priorities(int displayId) {
        SparseArray<?> votes = forDisplay(displayId);
        if (votes == null) {
            return Collections.emptyList();
        }
        java.util.ArrayList<Integer> out = new java.util.ArrayList<Integer>(votes.size());
        for (int i = 0; i < votes.size(); i++) {
            out.add(Integer.valueOf(votes.keyAt(i)));
        }
        return out;
    }
}
