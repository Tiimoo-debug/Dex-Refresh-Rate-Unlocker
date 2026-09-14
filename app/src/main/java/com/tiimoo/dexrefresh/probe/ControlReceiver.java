package com.tiimoo.dexrefresh.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.tiimoo.dexrefresh.core.Cfg;
import com.tiimoo.dexrefresh.core.ProbeLog;

/**
 * Lets you drive the probe from an on-device root shell, with no ADB involved:
 *
 * <pre>
 *   su -c 'am broadcast -a com.tiimoo.dexrefresh.ACTION_SNAPSHOT --es label dex-off'
 *   su -c 'am broadcast -a com.tiimoo.dexrefresh.ACTION_SNAPSHOT --es label dex-on'
 * </pre>
 *
 * <p>Labelling snapshots by hand is deliberate. Automatic DeX detection is a
 * guess until we know which signal this firmware updates; your label is ground
 * truth, and the whole method depends on the two sides of the diff being
 * correctly identified.
 *
 * <p>Also listens for Samsung desktop-mode broadcasts so we can find out which
 * (if any) of them actually fire on One UI 7.
 */
public final class ControlReceiver extends BroadcastReceiver {

    private ControlReceiver() {
    }

    /** Samsung desktop-mode broadcast candidates. Unverified on this device. */
    private static final String[] DEX_ACTIONS = {
            "com.samsung.intent.action.DESKTOP_MODE_CHANGED",
            "com.samsung.android.desktopmode.action.DESKTOP_MODE_STATE_CHANGED",
            "android.app.action.ENTER_KNOX_DESKTOP_MODE",
            "android.app.action.EXIT_KNOX_DESKTOP_MODE",
            "android.intent.action.HDMI_PLUGGED",
            "android.intent.action.DOCK_EVENT",
    };

    private static volatile boolean registered;

    public static synchronized void register(Object contextObj) {
        if (registered) {
            return;
        }
        if (!(contextObj instanceof Context)) {
            ProbeLog.post("control receiver NOT registered: no system Context "
                    + "(got " + contextObj + "). Snapshots still fire on display events.");
            return;
        }
        Context context = (Context) contextObj;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Cfg.ACTION_SNAPSHOT);
        filter.addAction(Cfg.ACTION_SCOUT);
        for (String a : DEX_ACTIONS) {
            filter.addAction(a);
        }
        ControlReceiver receiver = new ControlReceiver();
        try {
            // Android 14 requires an explicit export flag for dynamic receivers.
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            registered = true;
        } catch (Throwable t) {
            try {
                context.registerReceiver(receiver, filter);
                registered = true;
            } catch (Throwable t2) {
                ProbeLog.post("control receiver registration failed: %s / %s", t, t2);
                return;
            }
        }
        ProbeLog.post("control receiver registered; trigger a snapshot with:");
        ProbeLog.post("  su -c 'am broadcast -a %s --es label <your-label>'", Cfg.ACTION_SNAPSHOT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Runs on the main thread of system_server: do the parsing here, but
        // hand every dump off to the snapshot thread.
        try {
            String action = intent == null ? null : intent.getAction();
            if (action == null) {
                return;
            }
            if (Cfg.ACTION_SNAPSHOT.equals(action)) {
                final String label = stringExtra(intent, "label", "manual");
                final int depth = intExtra(intent, "depth", Cfg.SNAPSHOT_DEPTH);
                final boolean getters = boolExtra(intent, "getters", false);
                ProbeLog.post("snapshot requested: label=%s depth=%d getters=%s",
                        label, depth, getters);
                Snapshots.request(label, depth, getters);
                return;
            }
            if (Cfg.ACTION_SCOUT.equals(action)) {
                final String className = stringExtra(intent, "class", null);
                final boolean scan = boolExtra(intent, "scan", true);
                Snapshots.runLater(0, () -> runScout(className, scan));
                return;
            }
            // A DeX-ish broadcast we were guessing about actually fired.
            DeXState.lastBroadcast = action + " " + describeExtras(intent);
            DeXState.lastBroadcastAtMs = System.currentTimeMillis();
            ProbeLog.post("DEX-BROADCAST %s", DeXState.lastBroadcast);
            Snapshots.requestDeferred("broadcast:" + action);
        } catch (Throwable t) {
            ProbeLog.postThrowable("ControlReceiver.onReceive", t);
        }
    }

    private static void runScout(String className, boolean scan) {
        ClassLoader cl = ProbeState.systemServerClassLoader;
        if (className != null) {
            ProbeLog.postBlock(ClassScout.dumpClassSignature(cl, className));
        }
        if (scan) {
            Object dms = Snapshots.displayManagerService();
            if (dms == null) {
                ProbeLog.post("graph scan skipped: DisplayManagerService not resolved");
            } else {
                ProbeLog.postBlock(ClassScout.graphScan(dms, 4));
            }
        }
    }

    private static String describeExtras(Intent intent) {
        try {
            if (intent.getExtras() == null) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String key : intent.getExtras().keySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(key).append('=').append(intent.getExtras().get(key));
            }
            return sb.append('}').toString();
        } catch (Throwable t) {
            return "<extras unreadable: " + t + ">";
        }
    }

    private static String stringExtra(Intent intent, String key, String def) {
        try {
            String v = intent.getStringExtra(key);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private static int intExtra(Intent intent, String key, int def) {
        try {
            return intent.getIntExtra(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean boolExtra(Intent intent, String key, boolean def) {
        try {
            return intent.getBooleanExtra(key, def);
        } catch (Throwable t) {
            return def;
        }
    }
}
