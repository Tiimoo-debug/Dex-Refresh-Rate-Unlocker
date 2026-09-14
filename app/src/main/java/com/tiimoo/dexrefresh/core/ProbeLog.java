package com.tiimoo.dexrefresh.core;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XposedBridge;

/**
 * Asynchronous logger.
 *
 * <p>Everything the hooks emit goes through here. Hook callbacks in
 * system_server frequently run while DisplayManagerService locks are held, and
 * writing to logcat (let alone the LSPosed log file) from under a lock is a
 * good way to trip the system watchdog. So hook threads only enqueue an
 * already-formatted string and a daemon thread does the actual I/O.
 *
 * <p>The queue is bounded and drops on overflow rather than blocking. Dropping
 * probe lines is always preferable to stalling system_server.
 */
public final class ProbeLog {

    private static final int QUEUE_CAPACITY = 8192;

    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong SEQ = new AtomicLong();

    private static volatile boolean started;
    private static volatile boolean xposedLogAvailable = true;

    private ProbeLog() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                drainLoop();
            }
        }, Cfg.TAG + "-log");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Enqueue a line. Safe to call from any hook callback, never blocks. */
    public static void post(String line) {
        if (line == null) {
            return;
        }
        String stamped = SEQ.incrementAndGet() + "| " + line;
        if (!QUEUE.offer(stamped)) {
            DROPPED.incrementAndGet();
        }
    }

    public static void post(String fmt, Object... args) {
        try {
            post(String.format(java.util.Locale.US, fmt, args));
        } catch (Throwable t) {
            post(fmt + " <format failed: " + t + ">");
        }
    }

    /**
     * Write immediately on the calling thread. Only for module lifecycle
     * messages emitted at hook-install time, never from a hook callback.
     */
    public static void postNow(String line) {
        write(line);
    }

    public static void postThrowable(String context, Throwable t) {
        post(context + " -> " + t.getClass().getName() + ": " + t.getMessage());
    }

    /** Emit a multi-line block as individual lines so logcat does not truncate. */
    public static void postBlock(String block) {
        if (block == null) {
            return;
        }
        for (String line : block.split("\n", -1)) {
            post(line);
        }
    }

    private static void drainLoop() {
        List<String> batch = new ArrayList<>(64);
        while (true) {
            try {
                batch.clear();
                batch.add(QUEUE.take());
                QUEUE.drainTo(batch, 63);
                for (int i = 0; i < batch.size(); i++) {
                    write(batch.get(i));
                }
                long dropped = DROPPED.getAndSet(0);
                if (dropped > 0) {
                    write("!! dropped " + dropped + " probe lines (queue full)");
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // Never let the logger thread die.
                try {
                    Log.w(Cfg.TAG, "log drain error", t);
                } catch (Throwable ignored) {
                    // give up on this line
                }
            }
        }
    }

    private static void write(String line) {
        try {
            Log.i(Cfg.TAG, line);
        } catch (Throwable ignored) {
            // logcat unavailable; fall through to the Xposed log
        }
        if (xposedLogAvailable) {
            try {
                XposedBridge.log(Cfg.TAG + ": " + line);
            } catch (Throwable t) {
                // Not running under Xposed (or the bridge is gone). Stop trying,
                // logcat alone is fine.
                xposedLogAvailable = false;
            }
        }
    }
}
