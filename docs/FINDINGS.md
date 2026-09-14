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

## Run 2 — most of this turned out to be about the wrong display

The heartbeat worked: it printed the full vote table, which is what run 1 could
not do. But nearly every conclusion drawn from it concerned the **built-in
phone panel**, not the external monitor that is the actual goal.

Every DisplayInfo in the capture reads:

```
DisplayInfo{"Built-in Screen", displayId 0, real 1440 x 3088}
```

1440x3088 at a 120 Hz maximum is the phone. The target is a **144 Hz 2K external
monitor**, which is one of displays 2 / 6 (it was 7 in run 1 — ids are assigned
dynamically and move between sessions).

So the findings below about display 0 are accurate but beside the point, and the
external display produced almost no data at all.

### Why the external display was invisible

A bias in the probe, not in the device. Captured state was stored under single
keys — `modes`, `committed`, `refreshRateMode` — so whichever display last passed
through overwrote the others, and the built-in panel passes through constantly.
The snapshot's getter loop was also hardcoded to display ids `{0, 1, 2}`, which
never included the external display on this device.

Fixed: everything is now keyed per display (`modes:6`, `committed:<device name>`,
`name:6`), the heartbeat prints every display rather than one, and display ids
come from the live vote map instead of a hardcoded list.

### What the external display did show

Only its votes, and only these:

```
d2{p5=RenderVote(120,inf) p10=CombinedVote p13=RenderVote(0,120)}
d6{p5=RenderVote(120,inf) p10=CombinedVote p13=RenderVote(0,120)}
```

`p13 = RenderVote(0,120)` — a **120 Hz render ceiling on a 144 Hz panel**. That
is the single most interesting line in the run and it is on the external display,
not the phone. Whether it is the cap or merely tracks it is unresolved: no
DisplayInfo, no mode list and no committed specs were captured for that display,
so we do not yet know whether the framework even enumerates 144 Hz modes for it.

### Findings that concern the built-in panel only

### The panel can do 120 Hz at native resolution

```
supportedModes = Display$Mode[21]
  id=1, 1440x3088, fps=120.00001, alternativeRefreshRates=[10, 24, 30, 48, 60, 96]
active modeId = 3, defaultModeId = 3          <- a 60 Hz mode
```

So this is not a hardware or mode-list limitation. A 120 Hz mode at the native
resolution exists and is simply not being selected. That rules out the
"SupportedModesVote filtered the list" hypothesis entirely.

### The vote table, with and without restrictHighRefreshRate

```
restrictHRR=true   d0{p5=RenderVote(120,inf) p7=RenderVote(60,inf) p10=CombinedVote
                       p12=SizeVote(1440x3088) p13=RenderVote(0,120)}
restrictHRR=false  d0{p5=RenderVote(120,inf)                       p10=CombinedVote
                       p12=SizeVote(1440x3088) p13=RenderVote(0,120)}
global             d-1{p11=CombinedVote p19=CombinedVote}   <- merged into every display

committed true :   physical (60,60)  render (60,60)
committed false:   physical (10,60)  render (0,60)
```

### Where display 0's 60 Hz ceiling must be

`RenderVote.updateSummary` narrows: the summary min is the max of all vote mins,
the summary max is the min of all vote maxes. Taking only the votes visible
above, d0 works out to min 120 / max 120 — which would select the 120 Hz mode.
But 60 is what gets committed.

So the ceiling is inside something not being printed, and there are only three
candidates: the `CombinedVote` at p10 on d0, and the two **global** CombinedVotes
at p11 and p19, which merge into every display. p19 is near the top of the
priority range, which makes it the strongest suspect.

`CombinedVote` holds a `List<Vote>` and applies each in turn, so the wrapper name
says nothing about what it constrains. Printing only the wrapper is exactly what
hid the answer. Fixed: votes now expand recursively, so a CombinedVote renders as
`CombinedVote[RenderVote(0,60) SizeVote(...)]`.

### Priority names are gone from this firmware, permanently

The harvest still found nothing, and the stack traces explain why:

```
at s.intercept(r8-map-id-efedc8ef...)
at l.proceed(r8-map-id-efedc8ef...)
at android.content.FiedPriendlt.restrictHighRefreshRate(FiedPriendlt.java:-4)
at com.android.server.wm.RootWindowContainer.applySurfaceChangesTransaction$1(qb/98275629...)
```

This framework is R8-optimised: classes renamed (`FiedPriendlt`, `s`, `l`, `q0`),
and `static final int` constants inlined at their use sites and the field
declarations dropped. `Vote.class.getDeclaredFields()` therefore has nothing to
harvest — no probe change can recover the names. Priority meanings have to be
inferred from each vote's behaviour instead, which the expanded rendering now
makes possible.

### What calls restrictHighRefreshRate

Not DeX, on this evidence. Every call comes from
`RootWindowContainer.applySurfaceChangesTransaction`, reached from
`WindowManagerService.relayoutWindow` (a window relayout) or
`DisplayContent.requestDisplayUpdate` (a display change) — and via a Samsung
interceptor chain (`s.intercept` → `l.proceed` → `q0.callback`) wrapping the
SurfaceControl call. That is a general WindowManager-driven decision, not a DeX
code path. It flips with screen state and window changes, and the vote it files
only raises the floor to 60.

### A new lead: Samsung's DisplayInfo.refreshRateMode

`refreshRateMode : int = 2` on display 0 — a Samsung field, not AOSP, sitting at
2 while the panel is pinned to 60. Plausibly the Motion Smoothness setting. Now
surfaced in the heartbeat so its value can be correlated against the setting.

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

## Run 3 — answered. The cap is a global 60 Hz physical vote.

Displays finally identified, and the monitor's mode list obtained:

```
0 = "Built-in Screen"  1@120 2@96 3@60 ...              active=3   (60)
2 = "Desktop"          43@60  and nothing else          active=43  (60)
6 = "HDMI Screen"      22@60 23@144 24@120 25@100 ...   active=22  (60)   [stale]
7 = "HDMI Screen"      44@60 45@144 46@120 47@100 ...   active=44  (60)
```

**The monitor exposes 144 Hz.** Mode 45 on display 7 (23 on the stale 6). The
framework enumerates it correctly, and the display is running its 60 Hz mode
instead. So this is not an EDID or mode-enumeration problem, and the fix belongs
at the vote layer after all.

### The cap

```
d-1 (GLOBAL - merged into every display):
  p11 = CombinedVote[PhysicalVote(10,120) DisableRefreshRateSwitchingVote]
  p19 = CombinedVote[PhysicalVote(0,60)   DisableRefreshRateSwitchingVote]
```

`p19` is a **global physical refresh-rate cap of 60 Hz**, paired with
`DisableRefreshRateSwitchingVote`, at a very high priority (AOSP's maximum is
20). Global votes merge into every display's summary, so this pins the 144 Hz
monitor — and everything else — to 60 Hz and forbids switching.

This is what the earlier runs could not see: it never changes, so change-triggered
logging never printed it, and it was wrapped in a `CombinedVote` whose contents
were not being expanded.

### A second cap sits behind it

```
d7 = p5=RenderVote(120,inf)
     p10=CombinedVote[PhysicalVote(0,120) DisableRefreshRateSwitchingVote]
     p13=RenderVote(0,120)
```

Even with the global 60 removed, `p10` and `p13` cap this display at **120**, not
144 — the phone panel's maximum applied to an external display that can do more.
Reaching 144 therefore needs both layers addressed, not just the obvious one.

### A third thing, for the DeX desktop specifically

Display 2 is `"Desktop"`, uniqueId `virtual:android,1000,Desktop,0` — a virtual
display created by system_server — and it has **exactly one mode, 43 @ 60 Hz**.
Whatever is composited into that virtual display is produced at 60 Hz regardless
of what the monitor is doing. This is the same class of problem LibreDeX hit and
solved by patching the virtual display's advertised mode.

### Prime suspect for the global vote

`refreshRateMode = 2` on every display, and a global `PhysicalVote(0,60)` with
switching disabled, is exactly what **Motion Smoothness set to Standard** would
produce. That is a free test and it has to be ruled out before any code is
written to defeat the vote — writing a hook to override a setting the user can
simply change would be absurd.

Run 4 now prints the caller stack whenever a global vote or a vote capping
physical refresh below 120 is filed, which will name the subsystem responsible
either way.

## Changes made for run 4

- **Caller stack on global and physical-capping votes**, the change that should
  name whatever files the global 60 Hz cap.
- **Votes expand recursively.** A `CombinedVote` now renders its contents, so
  the three wrappers that currently hide the 60 Hz ceiling will show what they
  actually constrain. This is the one change that should end the hunt.
- **Compact mode list in the heartbeat** (`modes=1@120 2@96 3@60 ... active=3`),
  instead of a mode array truncated at 3942 characters.
- **Samsung's `DisplayInfo.refreshRateMode`** surfaced in the heartbeat.

## Open question after run 3

Answered by run 3: the framework does expose 144 Hz for the monitor, and a
global `PhysicalVote(0,60)` holds everything to 60.

What remains is **who files that vote**, and whether it is simply the Motion
Smoothness setting. Check the setting first; the caller stack added for run 4
answers it definitively either way.

Run 2 did not answer it, because the ceiling was again 60 for the whole capture
and the capture again did not span a DeX transition. But it is now cheap to
answer: one `STATE` line with DeX **disconnected** settles it.

- ceiling 120 → DeX is capping it, and the expanded CombinedVote will name the vote
- ceiling still 60 → the cap is global, and Motion Smoothness is the thing to
  check first, not DeX

Note that the two global votes (p11, p19 on display -1) are present regardless of
DeX and merge into every display. If the ceiling turns out to live in p19, that
points at a global policy rather than anything DeX-specific.
