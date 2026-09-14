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
 *   su -c 'setprop debug.dexrr.cmd "why"'              what caps each display
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
    private static volatile String lastPersisted;

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
        // Read once here so a reboot restores the selection...
        while (true) {
            try {
                Object raw = get.invoke(null, Cfg.PROP_CMD, "");
                String value = raw instanceof String ? ((String) raw).trim() : "";
                if (!value.isEmpty() && !value.equals(lastValue)) {
                    lastValue = value;
                    ProbeLog.post("COMMAND '%s'", value);
                    dispatch(value);
                }
                // ...and poll it too, so setting it mid-session takes effect
                // rather than silently waiting for the next boot.
                Object persisted = get.invoke(null, Cfg.PROP_PERSIST_UNLOCK, "");
                String persistedValue = persisted instanceof String
                        ? ((String) persisted).trim() : "";
                if (!persistedValue.equals(lastPersisted)) {
                    lastPersisted = persistedValue;
                    Unlock.applyPersisted(persistedValue);
                }
            } catch (Throwable t) {
                // Never let the poller die; a transient failure is not fatal.
                ProbeLog.postThrowable("command poller", t);
            }
            sleep(Cfg.CMD_POLL_MS);
        }
    }

    private static void dispatch(String value) {
        String[] parts = stripCacheBuster(value.split("\\s+"));
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
        if ("why".equals(verb)) {
            Integer requested = null;
            if (parts.length > 1) {
                try {
                    int parsed = Integer.parseInt(parts[1]);
                    // Display ids are small. Anything larger is not an id -
                    // most likely a timestamp that escaped the strip above.
                    if (parsed >= -1 && parsed <= 255) {
                        requested = Integer.valueOf(parsed);
                    } else {
                        ProbeLog.post("why: '%s' is not a display id, explaining all", parts[1]);
                    }
                } catch (NumberFormatException e) {
                    // a typo: explain everything rather than fail, since the id
                    // is the awkward part to get right in the first place.
                    requested = null;
                }
            }
            final Integer displayId = requested;
            Snapshots.runLater(0, new Runnable() {
                @Override
                public void run() {
                    if (displayId == null) {
                        Diagnose.explainAll();
                    } else {
                        Diagnose.explain(displayId.intValue());
                    }
                }
            });
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
                        ProbeLog.postReport(ClassScout.graphScan(dms, 4));
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
                    ProbeLog.postReport(ClassScout.dumpClassSignature(
                            ProbeState.systemServerClassLoader, className));
                }
            });
            return;
        }
        ProbeLog.post("unknown command '%s' (try: snapshot <label> | votes | scout"
                + " | class <fqcn> | why [displayId]"
                + " | unlock <display:priority,...> | unlock off)", value);
    }

    /**
     * Drop a trailing epoch timestamp.
     *
     * <p>Commands only fire when the property value changes, so callers append
     * $(date +%s) to make a repeated command take effect. That token then gets
     * parsed as an argument - "why $(date +%s)" was read as "explain display
     * 1789418903". Stripping it here fixes every verb at once rather than
     * teaching each one to ignore it.
     */
    private static String[] stripCacheBuster(String[] parts) {
        if (parts.length < 2) {
            return parts;
        }
        String last = parts[parts.length - 1];
        if (!looksLikeEpoch(last)) {
            return parts;
        }
        String[] out = new String[parts.length - 1];
        System.arraycopy(parts, 0, out, 0, out.length);
        return out;
    }

    private static boolean looksLikeEpoch(String token) {
        // No upper bound on length: the control panel stamps commands with
        // System.nanoTime(), which is 19 digits, and a shell caller might use
        // anything. Nine or more digits is never a display id or a priority.
        if (token.length() < 9) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
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
