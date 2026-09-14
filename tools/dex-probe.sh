#!/system/bin/sh
# On-device helper for the DeX refresh-rate probe. Run from a Termux root shell.
#
#   ./dex-probe.sh snapshot dex-off     take a labelled snapshot
#   ./dex-probe.sh votes [label]        vote table only, less noise
#   ./dex-probe.sh scout                class/field discovery
#   ./dex-probe.sh class <fqcn>         dump one class's fields + methods
#   ./dex-probe.sh watch                follow the probe log
#   ./dex-probe.sh save [file]          dump the log buffer to a file
#   ./dex-probe.sh bigbuffer            raise the logcat buffer to 64M
#   ./dex-probe.sh why [displayId]      list every vote capping each display
#   ./dex-probe.sh report               show snapshots/diagnoses, no noise
#   ./dex-probe.sh unlock <spec|off>    drop capping votes this session
#   ./dex-probe.sh persist <spec>       same, applied at every boot
#
# Everything here is read-only with respect to the device.

set -u

TAG=DexRRProbe
TAG_REPORT=DexRRReport

# Re-exec as root if we are not already.
if [ "$(id -u)" != "0" ]; then
    exec su -c "sh $0 $*"
fi

cmd=${1:-help}
PROP=debug.dexrr.cmd

# The property channel is used rather than `am broadcast`: broadcasts proved
# unreliable on this device (they fail outright from a non-root shell, and the
# receiver may never have registered), whereas setprop on a debug.* key always
# works from root and needs nothing on the module side but a poller.
case "$cmd" in
    snapshot)
        label=${2:-manual}
        # Vary the value so repeating the same label still triggers.
        setprop "$PROP" "snapshot $label $(date +%s)"
        echo "snapshot '$label' requested; it appears in the log within a few seconds"
        ;;
    votes)
        setprop "$PROP" "votes ${2:-votes} $(date +%s)"
        echo "vote table requested"
        ;;
    scout)
        setprop "$PROP" "scout $(date +%s)"
        echo "graph scan requested"
        ;;
    class)
        if [ $# -lt 2 ]; then
            echo "usage: $0 class <fully.qualified.ClassName>" >&2
            exit 2
        fi
        setprop "$PROP" "class $2"
        echo "signature dump of $2 requested"
        ;;
    why)
        # No id needed: display ids churn, so the default explains them all.
        setprop "$PROP" "why ${2:-} $(date +%s)"
        echo "asking what caps ${2:+display $2}${2:-every display}; watch the log"
        ;;
    unlock)
        if [ $# -lt 2 ]; then
            echo "usage: $0 unlock <display:priority,...|off>" >&2
            echo "   eg: $0 unlock -1:19            drop the global 60Hz cap" >&2
            echo "       $0 unlock '-1:19,*:10,*:13'  aim for 144Hz" >&2
            exit 2
        fi
        setprop "$PROP" "unlock $2 $(date +%s)"
        echo "unlock '$2' requested"
        ;;
    persist)
        # Survives reboots, unlike the session-only unlock above.
        setprop persist.dexrr.unlock "${2:-}"
        echo "persist.dexrr.unlock = '${2:-}'"
        ;;
    watch)
        exec logcat -s "$TAG":V
        ;;
    report|reports)
        # Reports only. The main tag emits ~60 lines/second while displays are
        # active, so a snapshot or diagnosis is buried within seconds and tail
        # will never show it.
        exec logcat -d -s "$TAG_REPORT":V
        ;;
    save)
        out=${2:-/sdcard/dexprobe-$(date +%Y%m%d-%H%M%S).txt}
        logcat -d -s "$TAG":V > "$out"
        echo "wrote $out ($(wc -l < "$out") lines)"
        ;;
    bigbuffer)
        # Boot-time output (the priority table, the class report) scrolls out of
        # the default ring buffer long before anyone looks at it.
        logcat -G 64M && echo "logcat buffer raised to 64M (resets on reboot)"
        ;;
    *)
        sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
        ;;
esac
