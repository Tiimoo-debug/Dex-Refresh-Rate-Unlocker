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
- **Not actually a DeX comparison.** Both `dex-off` and `dex-on` commands were
  sent back to back with no DeX session started in between, and the surrounding
  traffic (display state 2, brightness ramping 1.37 → 1.49) is a screen-on
  transition. So run 1 shows the mechanism, not a DeX diff.

### 4. Three displays exist

Ids 0, 2 and 7, with base mode ids 3, 43 and 44 respectively. Displays 2 and 7
sat at `physical (10.0 60.0)` throughout and were unaffected by the restrictor,
which only touched display 0.

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

## Open question for run 2

Before anything else: **is the phone set to Adaptive motion smoothness, and does
it actually run at 120 Hz outside DeX?** The ceiling was 60.0 in both halves of
run 1. If Motion Smoothness is on Standard, everything is 60 Hz by setting and
there is no DeX-specific cap to find in that capture. Confirm 120 Hz is live
first, then take the two snapshots.
