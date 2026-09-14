package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Command channel built on a system property instead of a broadcast.
 *
 * <p>The first on-device run showed the broadcast route failing in two
 * different ways: {@code am broadcast} from a plain Termux shell is refused
 * outright ("Failed transaction"), and even when it succeeded from a root shell
 * no snapshot appeared, meaning the receiver had never registered. A property
 * has none of those dependencies - no Context, no boot phase, no receiver, no
 * permissions beyond what a root shell already has:
 *
 * <pre>
 *   su -c 'setprop debug.dexrr.cmd "snapshot dex-off"'
 *   su -c 'setprop debug.dexrr.cmd "snapshot dex-on"'
 *   su -c 'setprop debug.dexrr.cmd "votes"'
 *   su -c 'setprop debug.dexrr.cmd "scout"'
 *   su -c 'setprop debug.dexrr.cmd "class com.android.server.display.mode.Vote"'
 *   su -c 'setprop debug.dexrr.cmd "why 9"'            what caps display 9
 *   su -c 'setprop debug.dexrr.cmd "unlock -1:19"'     drop a capping vote
 *   su -c 'setprop debug.dexrr.cmd "unlock off"'
 * </pre>
 *
 * <p>Any change to the value runs the command. Repeat a command by varying the
 * text, e.g. {@code "snapshot dex-on 2"}.
 */
public final class CommandPoller {

    private CommandPoller() {
    }

    private static volatile boolean started;
    private static volatile String lastValue;

    public static synchronized void start(final ClassLoader cl) {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(cl);
            }
        }, Cfg.TAG + "-cmd");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        ProbeLog.postNow("command poller started; trigger work with:");
        ProbeLog.postNow("  su -c 'setprop " + Cfg.PROP_CMD + " \"snapshot dex-off\"'");
    }

    private static void loop(ClassLoader cl) {
        Method get = propertyGetter(cl);
        if (get == null) {
            ProbeLog.post("command poller: SystemProperties.get unavailable, giving up");
            return;
        }
        // Let the system finish booting before the first read.
        sleep(15000L);
        // Second chance for the broadcast receiver. If onBootPhase never fired
        // (which is what the first device run suggests) this is where it gets
        // registered instead. Failure here is fine - the property channel below
        // does not depend on it.
        try {
            ControlReceiver.register(ProbeState.systemContext);
        } catch (Throwable t) {
            ProbeLog.postThrowable("late receiver registration", t);
        }
        // A selection stored in persist.dexrr.unlock survives reboots, so the
        // unlock can be a standing setting rather than something retyped after
        // every boot.
        try {
            Object persisted = get.invoke(null, Cfg.PROP_PERSIST_UNLOCK, "");
            if (persisted instanceof String) {
                Unlock.applyPersisted((String) persisted);
            }
        } catch (Throwable t) {
            ProbeLog.postThrowable("persisted unlock", t);
        }
        while (true) {
            try {
                Object raw = get.invoke(null, Cfg.PROP_CMD, "");
                String value = raw instanceof String ? ((String) raw).trim() : "";
                if (!value.isEmpty() && !value.equals(lastValue)) {
                    lastValue = value;
                    ProbeLog.post("COMMAND '%s'", value);
                    dispatch(value);
                }
            } catch (Throwable t) {
                // Never let the poller die; a transient failure is not fatal.
                ProbeLog.postThrowable("command poller", t);
            }
            sleep(Cfg.CMD_POLL_MS);
        }
    }

    private static void dispatch(String value) {
        String[] parts = value.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.US);
        if ("snapshot".equals(verb)) {
            String label = parts.length > 1 ? parts[1] : "manual";
            Snapshots.request(label, Cfg.SNAPSHOT_DEPTH, false);
            return;
        }
        if ("votes".equals(verb)) {
            // Just the vote table, for a quick before/after without the noise.
            Snapshots.request(parts.length > 1 ? parts[1] : "votes-only", 2, false);
            return;
        }
        if ("why".equals(verb) && parts.length > 1) {
            try {
                final int displayId = Integer.parseInt(parts[1]);
                Snapshots.runLater(0, new Runnable() {
                    @Override
                    public void run() {
                        Diagnose.explain(displayId);
                    }
                });
            } catch (NumberFormatException e) {
                ProbeLog.post("why: '%s' is not a display id", parts[1]);
            }
            return;
        }
        if ("unlock".equals(verb)) {
            Unlock.configure(parts.length > 1 ? parts[1] : "off");
            return;
        }
        if ("scout".equals(verb)) {
            Snapshots.runLater(0, new Runnable() {
                @Override
                public void run() {
                    Object dms = Snapshots.displayManagerService();
                    if (dms == null) {
                        ProbeLog.post("graph scan skipped: DisplayManagerService not resolved");
                    } else {
                        ProbeLog.postBlock(ClassScout.graphScan(dms, 4));
                    }
                }
            });
            return;
        }
        if ("class".equals(verb) && parts.length > 1) {
            final String className = parts[1];
            Snapshots.runLater(0, new Runnable() {
                @Override
                public void run() {
                    ProbeLog.postBlock(ClassScout.dumpClassSignature(
                            ProbeState.systemServerClassLoader, className));
                }
            });
            return;
        }
        ProbeLog.post("unknown command '%s' (try: snapshot <label> | votes | scout"
                + " | class <fqcn> | why <displayId>"
                + " | unlock <display:priority,...> | unlock off)", value);
    }

    private static Method propertyGetter(ClassLoader cl) {
        Class<?> sp = Reflect.cls(cl, "android.os.SystemProperties");
        return sp == null ? null : Reflect.method(sp, "get", String.class, String.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
