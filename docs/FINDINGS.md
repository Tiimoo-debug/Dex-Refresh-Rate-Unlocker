# Findings

## Run 1 — 2026-09-14, One UI 7 / Android 15, S22 Ultra

### The probe works end to end

The full chain is observable on the device, exactly as designed:

```
restrictHighRefreshRate(true)
  -> VOTE updateVote display=0 priority=7
  -> getDesiredDisplayModeSpecs(0)
  -> setDesiredDisplayModeSpecsLocked(...)
  -> SurfaceControl.setDesiredDisplayModeSpecs(...)
```

### 1. `SurfaceControl.restrictHighRefreshRate(boolean)` — Samsung, not AOSP

The significant find. This method does not exist in AOSP 15; it is Samsung's own
refresh-rate restrictor, and the One UI 7 counterpart of the `notifyHFRmode`
call LibreDeX found on One UI 8. Finding it is exactly what the "print the whole
SurfaceControl inventory rather than guessing the name" approach was for.

Its effect is tightly coupled, 3 ms apart:

```
17:12:34.870  restrictHighRefreshRate(true)   [was false]
17:12:34.873  VOTE display=0 priority=7 -> RenderVote{min=60.0, max=Infinity}   [was null]
17:12:34.882  specs(0): physical (60.0 60.0)   [was physical (10.0 60.0)]
```

and symmetrically on the way back:

```
17:12:49.465  VOTE display=0 priority=7 -> null
17:12:49.483  SurfaceControl.setDesiredDisplayModeSpecs(... physical (10.0 60.0) render (0.0 60.0))
17:12:54.631  restrictHighRefreshRate(false)   [was true]
```

### 2. …but this is not the cap we are hunting

The vote it files has `mMinRefreshRate=60.0, mMaxRefreshRate=Infinity`. It raises
the **floor**, it does not lower the ceiling: display 0 goes from
`physical (10.0 60.0)` to `physical (60.0 60.0)`. That is LTPO being stopped
from idling down to 10 Hz, not a 120 Hz panel being held to 60.

**The ceiling was already 60.0 in both states.** Whatever restricts the panel to
60 was active for the entire capture and never changed, so change-triggered
logging — which is what makes the vote log readable — never printed it. It is
sitting in the vote table as a constant, and the only way to see it is to dump
the whole table.

### 3. What went wrong

- **No snapshot ever ran.** No `SNAPSHOT` block appears in the log. `am
  broadcast` failed outright from the plain Termux shell ("Failed transaction"),
  and even from a root shell nothing fired, so the receiver was never
  registered. Snapshots are the only thing that dumps the full vote table, which
  is precisely what point 2 needs.
- **The priority table is empty.** Every vote logged as
  `priority=7(PRIORITY_?)`, meaning the harvest found no `PRIORITY` constants.
  So we cannot tell whether Samsung renumbered or the harvest simply broke —
  and priority 7 is *not* believable as AOSP's 7, because AOSP 15 numbers 7 as
  `PRIORITY_APP_REQUEST_SIZE`, which would be a `SizeVote`, not the `RenderVote`
  actually observed.
- **Boot output had already scrolled away.** The log begins at sequence 25601;
  the priority table, class report and SurfaceControl inventory are all printed
  at boot and were long gone.
- **Cross-display comparison bug (mine).** `getDesiredDisplayModeSpecs` is
  called once each for displays 0, 2 and 7 in a row, and the result dedupe was
  keyed on the method alone. Every `[was ...]` on those lines was comparing one
  display against a different one. Fixed: the key now includes the arguments.
- **The excerpt does not contain a DeX transition** — though DeX *was* in use.
  (An earlier version of this note wrongly said DeX was never started; it was.
  The first invocation was without DeX, the later ones with, and DeX was toggled
  on and off during the third.) The 26 seconds actually pasted are bracketed by
  `requestDisplayStateInternal(0, state 2)` — display state 1 → 2, i.e. screen
  off → on — followed by an auto-brightness ramp from 1.376 to 1.497. That is a
  screen wake, not a dock event. `logcat -s` was following live, so what got
  captured is whatever scrolled past during that window, and the DeX toggles
  fell outside it.

### 4. Three displays, all capped at 60, with DeX running

Ids 0, 2 and 7, with base mode ids 3, 43 and 44. Displays 2 and 7 sat at
`physical (10.0 60.0)` throughout, untouched by the restrictor, which only moved
display 0.

Since DeX was running during this capture, "every display has a 60 Hz ceiling
that never moves" is the central observation, not an aside. A cap that is
constant is precisely what a change-triggered log cannot show, which is why the
excerpt contains no line explaining it.

What displays 2 and 7 actually are is still unknown — the log rendered them only
as `DisplayDevice@hash`. That is now fixed: display events resolve name,
uniqueId, type and flags.

## Changes made for run 2

- **Property command channel**, replacing broadcasts as the primary trigger:
  `setprop debug.dexrr.cmd "snapshot dex-on"`. No Context, no boot phase, no
  receiver, no permission beyond a root shell. The broadcast receiver is kept as
  a secondary path and now falls back to `ActivityThread.getSystemContext()`.
- **Snapshots are self-contained**: they now carry the vote priority table and
  the Samsung SurfaceControl inventory, so boot output scrolling away no longer
  costs us anything.
- **Priority harvest made robust and self-diagnosing**: it sweeps `Vote`, its
  inner classes, `DisplayModeDirector`, `VoteSummary` and `VotesStorage`, and if
  it still finds nothing it dumps every static int on the Vote type so we can
  see what this firmware actually has.
- **`restrictHighRefreshRate` gets a dedicated hook that prints the caller
  stack.** A boolean does not say who decided; the stack does, and that is what
  will connect it to DeX or rule it out.
- **Per-argument result dedupe**, fixing the cross-display bug above.
- `dex-probe.sh bigbuffer` raises the logcat ring to 64 MB.

### Heartbeat, added because of this

The deeper lesson from run 1 is that change-triggered logging — the thing that
makes the vote log readable at frame rate — structurally cannot show a
constraint that is always present. Run 2 therefore prints the whole state on a
timer as well:

```
STATE* d0{p7=RenderVote(60,inf)} d2{} d7{} | committed=... | restrictHRR=true
```

Every 10 s when it differs, plus a forced anchor every 60 s so a constant reads
as a constant instead of as silence. Field reads only, no locks, no binder.

## Open question for run 2

Before anything else: **is the phone set to Adaptive motion smoothness, and does
it actually run at 120 Hz with DeX disconnected?** Every display showed a 60 Hz
ceiling for the whole capture. Two explanations fit equally well so far:

1. DeX caps everything to 60 — the hypothesis, and consistent with DeX being
   active throughout.
2. Motion smoothness is set to Standard, in which case 60 Hz is the global
   setting and there is nothing DeX-specific in that log at all.

The heartbeat distinguishes them without ambiguity: with the module running and
DeX *disconnected*, a `STATE` line showing a 120 Hz ceiling proves (1); one
still showing 60 proves (2). Check that before capturing the diff, because under
(2) no amount of further instrumentation will find a DeX cap that is not there.
