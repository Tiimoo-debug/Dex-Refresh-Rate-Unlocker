package com.tiimoo.dexrefresh.probe;

import com.tiimoo.dexrefresh.core.ProbeLog;
import com.tiimoo.dexrefresh.core.Reflect;

import java.util.Map;

/**
 * What the USB-C link actually negotiated.
 *
 * <p>This is not refresh-rate policy, and normally would not belong here. It
 * earns its place because one number decides whether a high rate is reachable
 * at all: how many of the port's four high-speed lanes went to DisplayPort.
 *
 * <p>A USB-C port carrying DisplayPort splits its lanes one of two ways, and
 * the choice is made during the Type-C alt-mode handshake, before Android's
 * display stack sees anything:
 *
 * <pre>
 *   pin assignment C / E   4 DP lanes, USB 2.0 only
 *   pin assignment D / F   2 DP lanes, USB 3.x works
 * </pre>
 *
 * <p>A dock offering ethernet and storage needs USB 3, so it negotiates two
 * lanes and half the display bandwidth goes away. When that happens the high
 * modes are missing from the display's mode list and no vote in the table is
 * responsible — which is exactly the situation the rest of this module cannot
 * diagnose, and would happily blame on a cap that was never there.
 *
 * <p>Android 14 added {@code DisplayPortAltModeInfo}, so the lane count is
 * readable rather than inferable. Everything here is a field read through
 * {@code UsbPortManager}; nothing is called, nothing is changed.
 *
 * <p>The power fields come along for free and answer a separate question:
 * whether the phone is sourcing power to the display or limiting what it will
 * source. That is a different negotiation from the lane split — power rides
 * VBUS, data rides the high-speed lanes — so a change in one is not evidence
 * about the other. Both are reported so the two stay told apart.
 */
public final class UsbLink {

    private UsbLink() {
    }

    /** com.android.server.usb.UsbPortManager, captured at construction. */
    public static volatile Object portManager;

    public static void report() {
        ProbeLog.postReport(describe());
    }

    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== USB-C link =====\n");
        Object manager = portManager;
        if (manager == null) {
            sb.append("UsbPortManager was not captured. It is built early in\n")
                    .append("system_server; if this module loaded after that, the\n")
                    .append("instance was missed and a reboot is needed.\n")
                    .append("Meanwhile: adb-free fallback is 'dumpsys usb'.\n");
            return sb.toString();
        }
        Object ports = Reflect.get(manager, "mPorts");
        if (!(ports instanceof Map)) {
            sb.append("mPorts not readable on this firmware (got ")
                    .append(ports == null ? "null" : ports.getClass().getName())
                    .append("). Fall back to 'dumpsys usb'.\n");
            return sb.toString();
        }
        Map<?, ?> map = (Map<?, ?>) ports;
        if (map.isEmpty()) {
            sb.append("no ports known.\n");
            return sb.toString();
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sb.append("port ").append(entry.getKey()).append(":\n");
            describePort(sb, entry.getValue());
        }
        return sb.toString();
    }

    private static void describePort(StringBuilder sb, Object portInfo) {
        Object status = Reflect.get(portInfo, "mUsbPortStatus");
        if (status == null) {
            sb.append("   no status\n");
            return;
        }
        sb.append("   connected:      ").append(nonZero(status, "mCurrentMode")).append('\n');
        sb.append("   power role:     ").append(powerRole(status)).append('\n');
        sb.append("   data role:      ").append(dataRole(status)).append('\n');

        Object limited = Reflect.get(status, "mPowerTransferLimited");
        if (limited != null) {
            sb.append("   power limited:  ").append(limited);
            if (Boolean.TRUE.equals(limited)) {
                sb.append("   <- the phone is capping what it will source");
            }
            sb.append('\n');
        }

        Object dp = Reflect.get(status, "mDisplayPortAltModeInfo");
        if (dp == null) {
            sb.append("   DisplayPort:    no alt mode info (nothing plugged, or the\n")
                    .append("                   HAL does not report it on this device)\n");
            return;
        }
        Object lanes = Reflect.get(dp, "mNumLanes");
        sb.append("   DP lanes:       ").append(lanes == null ? "?" : lanes);
        if (lanes instanceof Number) {
            int n = ((Number) lanes).intValue();
            if (n == 2) {
                sb.append("   <- HALF bandwidth. The link kept USB 3 for the\n")
                        .append("                      dock's other devices and gave DisplayPort\n")
                        .append("                      two lanes instead of four.");
            } else if (n == 4) {
                sb.append("   <- full bandwidth; the rate limit is not the link");
            }
        }
        sb.append('\n');
        sb.append("   link training:  ").append(linkTraining(dp)).append('\n');
        sb.append("   hot plug:       ").append(Reflect.get(dp, "mHotPlugDetect")).append('\n');
        sb.append("   sink status:    ").append(sinkStatus(dp)).append('\n');
    }

    private static String powerRole(Object status) {
        Object value = Reflect.get(status, "mCurrentPowerRole");
        if (!(value instanceof Number)) {
            return "?";
        }
        switch (((Number) value).intValue()) {
            case 1: return "SOURCE (the phone is powering the other end)";
            case 2: return "SINK (the phone is being powered)";
            default: return "none";
        }
    }

    private static String dataRole(Object status) {
        Object value = Reflect.get(status, "mCurrentDataRole");
        if (!(value instanceof Number)) {
            return "?";
        }
        switch (((Number) value).intValue()) {
            case 1: return "HOST";
            case 2: return "DEVICE";
            default: return "none";
        }
    }

    private static String linkTraining(Object dp) {
        Object value = Reflect.get(dp, "mLinkTrainingStatus");
        if (!(value instanceof Number)) {
            return "?";
        }
        switch (((Number) value).intValue()) {
            case 1: return "success";
            case 2: return "FAILURE   <- the link came up and then failed to train";
            default: return "unknown";
        }
    }

    private static String sinkStatus(Object dp) {
        Object value = Reflect.get(dp, "mPartnerSinkStatus");
        if (!(value instanceof Number)) {
            return "?";
        }
        switch (((Number) value).intValue()) {
            case 1: return "not capable of DisplayPort";
            case 2: return "capable, but disabled";
            case 3: return "enabled";
            default: return "unknown";
        }
    }

    private static String nonZero(Object status, String field) {
        Object value = Reflect.get(status, field);
        return value instanceof Number && ((Number) value).intValue() != 0 ? "yes" : "no";
    }
}
