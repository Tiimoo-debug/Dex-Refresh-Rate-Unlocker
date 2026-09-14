package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Best-effort "is DeX running right now?" hints.
 *
 * <p>Deliberately advisory. The probe never gates behaviour on this, it only
 * annotates snapshots so the log is readable afterwards. Ground truth for the
 * before/after comparison comes from you labelling the snapshot by hand
 * (see docs/USAGE.md) — that is always correct, whereas every automatic signal
 * below is a guess until we have confirmed which one this firmware actually
 * updates.
 *
 * <p>All of these read system properties or previously observed broadcast
 * state. Nothing here may be called from inside a hook callback: property reads
 * are cheap but the display enumeration is a binder call.
 */
public final class DeXState {

    private DeXState() {
    }

    /** Updated by ControlReceiver when a DeX-ish broadcast arrives. */
    public static volatile String lastBroadcast = "none";
    public static volatile long lastBroadcastAtMs;

    /**
     * System properties that plausibly carry DeX / desktop-mode state on One UI.
     * Unverified on this device: the snapshot prints whichever ones exist so we
     * can find out which is real rather than assuming.
     */
    private static final String[] CANDIDATE_PROPS = {
            "sys.dex.state",
            "sys.desktopmode.state",
            "sys.samsung.dex.state",
            "persist.sys.dex.mode",
            "sys.display.dex_mode",
            "sys.hdmi.connected",
            "persist.sys.desktop_mode",
            "sys.boot_completed",
    };

    /** Report of every candidate signal, for the snapshot header. */
    public static String hints() {
        StringBuilder sb = new StringBuilder();
        sb.append("lastDexBroadcast=").append(lastBroadcast);
        if (lastBroadcastAtMs > 0) {
            sb.append(" (").append(System.currentTimeMillis() - lastBroadcastAtMs)
                    .append("ms ago)");
        }
        List<String> props = readProps();
        sb.append("  props{");
        for (int i = 0; i < props.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(props.get(i));
        }
        sb.append('}');
        return sb.toString();
    }

    private static List<String> readProps() {
        List<String> out = new ArrayList<>();
        Class<?> sp = Reflect.cls(ProbeState.systemServerClassLoader != null
                ? ProbeState.systemServerClassLoader
                : DeXState.class.getClassLoader(), "android.os.SystemProperties");
        if (sp == null) {
            return out;
        }
        Method get = Reflect.method(sp, "get", String.class, String.class);
        if (get == null) {
            return out;
        }
        for (String key : CANDIDATE_PROPS) {
            try {
                Object v = get.invoke(null, key, "");
                if (v instanceof String && !((String) v).isEmpty()) {
                    out.add(key + "=" + v);
                }
            } catch (Throwable ignored) {
                // property unreadable under this SELinux context; skip
            }
        }
        return out;
    }

    /** Best-effort activation marker so StatusActivity can confirm we loaded. */
    public static void markActive(ClassLoader cl, String propName) {
        try {
            Class<?> sp = Reflect.cls(cl, "android.os.SystemProperties");
            Method set = Reflect.method(sp, "set", String.class, String.class);
            if (set != null) {
                set.invoke(null, propName, String.valueOf(System.currentTimeMillis() / 1000L));
            }
        } catch (Throwable t) {
            // SELinux commonly refuses this. Not a problem: it is only a
            // convenience for the status screen.
            ProbeLog.postNow("could not set " + propName + " (expected on some builds): " + t);
        }
    }
}
