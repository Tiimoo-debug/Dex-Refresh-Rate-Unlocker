package com.tiimoo.dexrefresh.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two independent noise filters.
 *
 * <p>{@link #changed} is the important one: hot paths such as
 * {@code VotesStorage.updateVote} fire constantly with identical arguments, and
 * what we actually want is the *transitions*. Logging only on change turns a
 * firehose into a readable diff of "what moved when DeX started".
 *
 * <p>{@link #allow} is the safety net for sites whose arguments genuinely churn.
 */
public final class Throttle {

    private static final int MAX_KEYS = 4096;

    private static final ConcurrentHashMap<String, String> LAST_VALUE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, long[]> BUCKETS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> SUPPRESSED = new ConcurrentHashMap<>();

    private Throttle() {
    }

    /** True when {@code value} differs from the last value seen for {@code key}. */
    public static boolean changed(String key, String value) {
        String v = value == null ? "<null>" : value;
        if (LAST_VALUE.size() > MAX_KEYS) {
            LAST_VALUE.clear();
        }
        String prev = LAST_VALUE.put(key, v);
        return prev == null || !prev.equals(v);
    }

    /** Previous value for a key, or null. Useful for printing before -> after. */
    public static String previous(String key) {
        return LAST_VALUE.get(key);
    }

    /**
     * Token bucket. Returns true if this event may be logged. Once a window's
     * budget is spent, further events are counted and reported by
     * {@link #takeSuppressed}.
     */
    public static boolean allow(String key) {
        return allow(key, Cfg.RATE_LIMIT_PER_WINDOW, Cfg.RATE_LIMIT_WINDOW_MS);
    }

    public static boolean allow(String key, int maxPerWindow, long windowMs) {
        if (BUCKETS.size() > MAX_KEYS) {
            BUCKETS.clear();
            SUPPRESSED.clear();
        }
        long now = System.currentTimeMillis();
        long[] bucket = BUCKETS.get(key);
        if (bucket == null) {
            long[] created = new long[]{now, 0L};
            long[] raced = BUCKETS.putIfAbsent(key, created);
            bucket = raced != null ? raced : created;
        }
        synchronized (bucket) {
            if (now - bucket[0] >= windowMs) {
                bucket[0] = now;
                bucket[1] = 0L;
                AtomicLong s = SUPPRESSED.get(key);
                if (s != null) {
                    s.set(0);
                }
            }
            if (bucket[1] < maxPerWindow) {
                bucket[1]++;
                return true;
            }
        }
        AtomicLong counter = SUPPRESSED.get(key);
        if (counter == null) {
            AtomicLong created = new AtomicLong();
            AtomicLong raced = SUPPRESSED.putIfAbsent(key, created);
            counter = raced != null ? raced : created;
        }
        counter.incrementAndGet();
        return false;
    }

    /** Number of events suppressed for a key since the last call, then resets. */
    public static long takeSuppressed(String key) {
        AtomicLong s = SUPPRESSED.get(key);
        return s == null ? 0L : s.getAndSet(0);
    }

    /** Forget everything. Called when a snapshot starts so it logs full state. */
    public static void reset() {
        LAST_VALUE.clear();
        BUCKETS.clear();
        SUPPRESSED.clear();
    }
}
