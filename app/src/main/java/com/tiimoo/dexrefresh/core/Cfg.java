package com.tiimoo.dexrefresh.core;

import java.util.regex.Pattern;

/** Central knobs and constants for the probe. */
public final class Cfg {

    private Cfg() {
    }

    /** logcat tag. Capture with: logcat -s DexRRProbe:V */
    public static final String TAG = "DexRRProbe";

    /**
     * Separate tag for reports - snapshots, vote diagnostics, class dumps.
     *
     * <p>The main tag emits around sixty lines a second while displays are
     * active, so a report is thousands of lines back within seconds of being
     * printed and `tail` will never show it. Reports get their own tag so they
     * can be read on their own:
     *
     *   su -c 'logcat -d -s DexRRReport:V'
     */
    public static final String TAG_REPORT = "DexRRReport";

    public static final String PKG = "com.tiimoo.dexrefresh";

    /** Manually trigger a labelled snapshot (see docs/USAGE.md). */
    public static final String ACTION_SNAPSHOT = PKG + ".ACTION_SNAPSHOT";
    /** Dump class/field/method inventories for the display subsystem. */
    public static final String ACTION_SCOUT = PKG + ".ACTION_SCOUT";

    /**
     * Property-based command channel, polled by {@link
     * com.tiimoo.dexrefresh.probe.CommandPoller}.
     *
     * <p>This exists because the broadcast route proved unreliable in practice:
     * `am broadcast` from a non-root Termux shell fails outright, and even from
     * a root shell the receiver may never have registered. A property needs no
     * Context, no boot phase and no receiver - system_server can always read
     * one, and `setprop` on a debug.* key is always permitted from a root
     * shell. Any change to the value triggers the command.
     *
     *   su -c 'setprop debug.dexrr.cmd "snapshot dex-off"'
     */
    public static final String PROP_CMD = "debug.dexrr.cmd";

    /**
     * Durable unlock selection, applied at boot. Survives reboots, which the
     * in-memory selection deliberately does not.
     *
     *   su -c 'setprop persist.dexrr.unlock "-1:19,*:10,*:13"'
     */
    public static final String PROP_PERSIST_UNLOCK = "persist.dexrr.unlock";

    /** How often the command property is polled. */
    public static final long CMD_POLL_MS = 2000L;

    /**
     * Heartbeat cadence. The vote log only prints transitions, so a constraint
     * that never moves never appears - which is exactly what hid the 60 Hz
     * ceiling in run 1. The heartbeat prints the whole state on a timer so a
     * constant shows up as a constant instead of as silence.
     */
    public static final long HEARTBEAT_MS = 10_000L;

    /** Print an anchor line this often even when nothing changed. */
    public static final long HEARTBEAT_ANCHOR_MS = 60_000L;

    /**
     * Set by the hook once it is live inside system_server, so StatusActivity
     * can tell you whether the module actually loaded. Best-effort: SELinux may
     * refuse the write, in which case the status simply reads "unknown".
     */
    public static final String PROP_ACTIVE = "sys.dexrr.active";

    /**
     * Field/method names we consider interesting. This is the brute-force net:
     * everything matching gets dumped. Deliberately wider than the obvious
     * "refresh" so Samsung's own naming (HFR / VRR / "brr") is caught too.
     */
    public static final Pattern INTERESTING = Pattern.compile(
            "(?i)refresh|mode|display|fps|hz|rate|vote|vrr|hfr|brr|frame|dex|desktop|peak|seamless");

    /**
     * Methods we hook by name. Much narrower than {@link #INTERESTING}: this one
     * decides what gets *hooked*, and hooking a hot method inside system_server
     * (getDisplayInfoInternal, and friends) is a good way to make the device
     * unusable. Keep it specific.
     */
    public static final Pattern HOOKABLE_METHOD = Pattern.compile(
            // "displaymode" covers setDesiredDisplayModeSpecsLocked,
            // updateDisplayModesLocked and getUserPreferredDisplayModeInternal.
            // Android 15 has both requestDisplayPower (the method LibreDeX
            // reflects into) and requestDisplayStateInternal, so match either.
            "(?i)displaymode|modespecs|refreshrate|framerate|activemode"
                    + "|requestdisplaystate|requestdisplaypower|modechanged"
                    + "|notifyhfr|hfrmode|vrrmode|peakrefresh|setmodeid"
                    + "|preferredmode|basemode");

    /**
     * Never hook these, however well their names match.
     *
     * <p>Verified against AOSP 14: getDisplayInfoForFrameRateOverride and
     * sendDisplayEventFrameRateOverrideLocked hang off getDisplayInfoInternal,
     * which every app hits whenever it asks about the display. Hooking a
     * per-query path in system_server costs an allocation per call for no
     * information we do not get elsewhere. The rest are trivial validators
     * whose arguments tell us nothing.
     */
    public static final Pattern NEVER_HOOK = Pattern.compile(
            "(?i)getdisplayinfo|framerateoverridechanged$|senddisplayevent"
                    + "|isvalidrefreshrate|refreshratesequals|isresolutionandrefreshratevalid");

    /** Methods whose invocation should schedule a fresh snapshot. */
    public static final Pattern SNAPSHOT_TRIGGER_METHOD = Pattern.compile(
            // Verified against AOSP 14 LogicalDisplayMapper: the real names are
            // handleDisplayDeviceAddedLocked / handleDisplayDeviceRemovedLocked /
            // onDisplayDeviceEventLocked / onDisplayDeviceChangedLocked.
            // Deliberately does NOT match updateLogicalDisplaysLocked, which is
            // far too hot to hang a snapshot off.
            "(?i)displaydeviceadded|displaydeviceremoved|displaydeviceevent"
                    + "|displaydevicechanged|logicaldisplayadded|logicaldisplayremoved");

    /** Per-hook-site log budget. Beyond this the site is muted for the window. */
    public static final int RATE_LIMIT_PER_WINDOW = 40;
    public static final long RATE_LIMIT_WINDOW_MS = 60_000L;

    /** Depth for in-hook object dumps. Snapshots use {@link #SNAPSHOT_DEPTH}. */
    public static final int HOOK_DUMP_DEPTH = 1;
    public static final int SNAPSHOT_DEPTH = 4;

    /** Hard caps so a runaway dump can never wedge system_server. */
    public static final int MAX_DUMP_LINES = 600;
    public static final int MAX_VALUE_CHARS = 240;
    public static final int MAX_CONTAINER_ITEMS = 32;

    /** Delay before an event-triggered snapshot, to let the display settle. */
    public static final long SNAPSHOT_SETTLE_MS = 1500L;

    /**
     * Floor on automatic snapshots. Display events arrive in bursts while DeX
     * sets itself up; without this a docking sequence would emit a dozen
     * near-identical multi-page dumps. Manual snapshots ignore this.
     */
    public static final long SNAPSHOT_MIN_INTERVAL_MS = 10_000L;
}
