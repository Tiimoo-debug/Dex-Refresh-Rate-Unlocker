#!/system/bin/sh
# On-device helper for the DeX refresh-rate probe. Run from a Termux root shell.
#
#   ./dex-probe.sh snapshot dex-off     take a labelled snapshot
#   ./dex-probe.sh scout                class/field discovery
#   ./dex-probe.sh class <fqcn>         dump one class's fields + methods
#   ./dex-probe.sh watch                follow the probe log
#   ./dex-probe.sh save [file]          dump the log buffer to a file
#
# Everything here is read-only with respect to the device.

set -u

ACTION_SNAPSHOT=com.tiimoo.dexrefresh.ACTION_SNAPSHOT
ACTION_SCOUT=com.tiimoo.dexrefresh.ACTION_SCOUT
TAG=DexRRProbe

# Re-exec as root if we are not already.
if [ "$(id -u)" != "0" ]; then
    exec su -c "sh $0 $*"
fi

cmd=${1:-help}

case "$cmd" in
    snapshot)
        label=${2:-manual}
        depth=${3:-4}
        am broadcast -a "$ACTION_SNAPSHOT" --es label "$label" --ei depth "$depth" >/dev/null
        echo "snapshot '$label' requested; it appears in the log within a second or two"
        ;;
    scout)
        am broadcast -a "$ACTION_SCOUT" --ez scan true >/dev/null
        echo "graph scan requested"
        ;;
    class)
        if [ $# -lt 2 ]; then
            echo "usage: $0 class <fully.qualified.ClassName>" >&2
            exit 2
        fi
        am broadcast -a "$ACTION_SCOUT" --es class "$2" --ez scan false >/dev/null
        echo "signature dump of $2 requested"
        ;;
    watch)
        exec logcat -s "$TAG":V
        ;;
    save)
        out=${2:-/sdcard/dexprobe-$(date +%Y%m%d-%H%M%S).txt}
        logcat -d -s "$TAG":V > "$out"
        echo "wrote $out ($(wc -l < "$out") lines)"
        ;;
    *)
        sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
        ;;
esac
