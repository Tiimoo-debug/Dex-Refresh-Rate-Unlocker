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

### Not the Motion Smoothness setting

The obvious explanation for a global `PhysicalVote(0,60)` with switching
disabled was Motion Smoothness on Standard. **Ruled out: the device is set to
Adaptive.** So something else installs a global 60 Hz cap on a phone whose panel
is allowed to run at 120 and whose monitor can do 144 — which makes the vote a
legitimate target rather than a setting to respect.

Run 4 prints the caller stack whenever a global vote, or any vote capping
physical refresh below 120, is filed. That will name the subsystem responsible,
the same way it identified restrictHighRefreshRate's caller.

Still worth confirming with one capture: **is p19 present with DeX
disconnected?** If it only appears while docked, it is DeX's cap and the hunt is
over. Every capture so far has been with DeX connected, so this has never
actually been tested.

## Phase 2 — selective vote suppression (opt-in, off by default)

The filtering point is identified, so the module can now suppress it. It stays
observation-only until explicitly told otherwise, and a reboot clears it.

```sh
su -c 'setprop debug.dexrr.cmd "unlock -1:19"'       # drop global priority 19
su -c 'setprop debug.dexrr.cmd "unlock -1:19,7:10"'  # and display 7 priority 10
su -c 'setprop debug.dexrr.cmd "unlock off"'
```

### Why it drops named (display, priority) pairs rather than applying a rule

The tempting design is "drop any vote that caps physical refresh below the panel
maximum". That would also drop **thermal throttling**, which exists to stop the
phone cooking itself, and low-power caps. Because R8 stripped the priority
constants from this firmware, the module cannot distinguish a thermal vote from
a DeX vote by name — and guessing wrong means silently disabling thermal
protection.

So the votes are named explicitly, read off the log. Precise, reversible, and it
doubles as the experiment: drop the suspect, watch the next `STATE` line, see
whether the monitor moves to 144.

Votes already filed before the unlock is configured are actively cleared (via
`invokeOriginalMethod`, so the clearing call is not caught by the suppression
hook). Suppressing future calls alone would not help: the cap is filed once and
then never touched again, which is precisely why it took three runs to find.

### Expected order of attack

1. `unlock -1:19` — the global 60 Hz cap. If the monitor jumps to 120, the
   remaining limit is the per-display 120 cap.
2. `unlock -1:19,7:10` — adds display 7's `PhysicalVote(0,120)`. Check whether
   `p13 = RenderVote(0,120)` then becomes the binding constraint.
3. The DeX `"Desktop"` virtual display's single 60 Hz mode is a separate
   problem that votes cannot fix — it needs the advertised mode patched, as
   LibreDeX did.

## Changes made for run 4

- **Caller stack on global and physical-capping votes**, the change that should
  name whatever files the global 60 Hz cap.
- **Votes expand recursively.** A `CombinedVote` now renders its contents, so
  the three wrappers that currently hide the 60 Hz ceiling will show what they
  actually constrain. This is the one change that should end the hunt.
- **Compact mode list in the heartbeat** (`modes=1@120 2@96 3@60 ... active=3`),
  instead of a mode array truncated at 3942 characters.
- **Samsung's `DisplayInfo.refreshRateMode`** surfaced in the heartbeat.

## Run 4 — it works. Both panels at 120 Hz.

Dropping the single global vote did it:

```sh
su -c 'setprop debug.dexrr.cmd "unlock -1:19"'
```

| | before | after |
| --- | --- | --- |
| phone panel | baseModeId=3, physical (60,60) | **baseModeId=1, physical (120,120)** |
| HDMI screen | active mode 44 = 60 Hz | **active mode 46/67/88 = 120 Hz** |
| DeX Desktop VD | physical (10,60) | physical (10,120) |

One global `PhysicalVote(0,60)` was holding *both* panels to 60 Hz. Removing it
gives 120 Hz on the phone and the external display simultaneously — which,
per the device owner, is better than One UI 5 ever managed: there the phone
panel was pinned at 60 while the external ran 144, and DeX dropped to 50 Hz
whenever the phone screen came on.

### What still caps at 120

```
d-1{ p11=CombinedVote[PhysicalVote(10,120) DisableRefreshRateSwitchingVote] }  <- GLOBAL
d9 { p5 =RenderVote(120,inf)
     p10=CombinedVote[PhysicalVote(0,120) DisableRefreshRateSwitchingVote]
     p13=RenderVote(0,120) }
```

Three caps, not two, and the first one is easy to miss:

- **`-1:11` is global** and caps physical refresh at 120 on every display. On its
  own it is enough to hold the monitor at 120 no matter what the per-display
  votes say.
- `9:10` caps physical at 120 for that display.
- `9:13` caps **render** at 120.

Dropping only the per-display pair leaves the global one standing and the
monitor stays at 120 — which is precisely what happened on the first attempt at
144. Having found the global 60 Hz cap at priority 19 it was easy to overlook
priority 11 sitting in the same global bucket.

That is what `why <displayId>` now exists for: it walks the per-display *and*
global votes, expands nested CombinedVotes, and reports every vote holding a
display below the fastest mode it advertises, with the exact `unlock` spec to
drop them. Reading a vote table by eye does not scale.

### Display ids are not stable

The HDMI screen moved **6 → 7 → 8 → 9 within a single session** — it is
re-created on every mode change. A rule pinned to one id works once and then
silently stops working after a redock. Hence wildcard targeting:

```sh
su -c 'setprop debug.dexrr.cmd "unlock -1:19,-1:11,*:10,*:13"'
```

`*:P` drops priority P on every display, however the ids are renumbered. The
global entries are written explicitly as `-1:` because the global bucket is
always display -1 and never churns.

### The complete constraint set for 144 Hz

Display 9 is the monitor, fastest advertised mode 87 @ 144. Everything that
narrows its summary (on Android 15 the maximum is the minimum of all vote
maxima):

| where | priority | vote | effect |
| --- | --- | --- | --- |
| global | 19 | `PhysicalVote(0,60)` | physical max 60 |
| global | 11 | `PhysicalVote(10,120)` | physical max 120 |
| display 9 | 10 | `PhysicalVote(0,120)` | physical max 120 |
| display 9 | 13 | `RenderVote(0,120)` | render max 120 |
| display 9 | 5 | `RenderVote(120,inf)` | render **floor** 120 — harmless, keep |

Physical 144 needs 19, 11 and 10 gone; frames at 144 needs 13 as well. Dropping
11 and 10 also removes their `DisableRefreshRateSwitchingVote`, which pins the
display to a single mode and has to go regardless.

For display 0 — the phone, maximum 120 — priorities 11, 10 and 13 all cap at
exactly 120, which *is* its maximum, so they constrain nothing there. Only 19
did. That is why dropping 19 alone produced 120 Hz on both panels.

### `auto`, so the set never has to be retyped

```sh
su -c 'setprop persist.dexrr.unlock "auto"'
```

The module computes the table above itself, per display, against each display's
own maximum, and re-checks continuously — surviving redocks, display
re-creation and reboots. Verified to produce exactly `[10, 11, 13, 19]` from the
observed vote table, and only `[19]` when the phone is alone.

### Making it permanent

```sh
su -c 'setprop persist.dexrr.unlock "-1:19,-1:11,*:10,*:13"'
```

Read and applied at boot. `setprop persist.dexrr.unlock ""` stops it.

## A confounder that applies to everything above

**DispUnlock has been enabled on this device for every measurement in this
document**, from before this module existed. So the vote table analysed
throughout is not stock Samsung — it is Samsung *plus another display module*,
and some entries may belong to that module rather than the vendor.

One entry looks like a module's work in hindsight:

```
p5 = RenderVote(120, inf)      on display 0, on "Desktop", and on the HDMI display
```

That is a render-rate **floor** of 120 — "never go below 120" — present on every
display including the phone panel. A floor is not what a vendor files to cap
something; it is what a module files to *force* high refresh. It has been
described as "harmless, keep it" throughout this document on the assumption it
was Samsung's. That assumption was never checked.

If it is DispUnlock's, it also suggests a cleaner explanation for the dock going
black than the one offered below:

- the unlock removes the ceilings and the mode pinning
- DispUnlock holds a 120 Hz floor
- a link that cannot reach 120 then has **no satisfiable mode at all**, so
  nothing comes up rather than falling back to 60

Also unknown: whether stock exposes 144 Hz modes for the external display at
all, or whether DispUnlock is what makes them visible. If the latter, "the
monitor advertises 144" is a fact about DispUnlock, not about the monitor.

### Attributing a vote

`trace <priority>` turns on caller-stack logging for that priority; re-plug the
display so the vote is re-filed, then read the reports. A vote filed by another
Xposed module shows that module's own classes in the stack; one filed by the
framework shows framework classes. There is a button for priority 5.

The full picture needs one more comparison: the vote table with DispUnlock
**disabled**, which separates Samsung's votes from the module's for good.

## Dock regression — read this before enabling the unlock

After `-1:19,-1:11,*:10,*:13` was set as a persisted unlock, a USB-C dock that
had previously worked stopped bringing up its display, while power delivery and
ethernet kept working. A direct USB-C cable to the same monitor still worked.

**Suspect the unlock first.** Those entries remove two things, not one:

```
-1:11  CombinedVote[PhysicalVote(10,120) DisableRefreshRateSwitchingVote]
*:10   CombinedVote[PhysicalVote(0,120)  DisableRefreshRateSwitchingVote]
```

the 120 Hz ceiling **and** `DisableRefreshRateSwitchingVote`, which pins a
display to a single mode.

### A wrong conclusion, withdrawn

An earlier version of this note argued that the dock gets two DisplayPort lanes
instead of four, and that 144 Hz through it was therefore never possible. That
was built on an invalid comparison and is withdrawn.

The comparison was:

| | rate | software state |
| --- | --- | --- |
| through the dock | 60 Hz | **stock** — no DispUnlock, no this module |
| direct cable | 120 Hz | **after** the unlock removed the global 60 Hz cap |

Two different software states. And **DeX is 60 Hz by nature** — that is this
project's entire premise, and it is precisely the vote already identified:

```
d-1 p19 = CombinedVote[PhysicalVote(0,60) DisableRefreshRateSwitchingVote]
```

So the dock's 60 Hz is completely explained by the cap we already found. It says
nothing about how many lanes that dock negotiates. "An exact halving means two
lanes" was numerology stacked on a bad comparison.

### What is actually known about the dock

Nothing yet. The dock's mode list has never been captured, and the dock has
never been tested with this module in **any** state — only on stock, and with
the unlock already active. Both of those differ from the state that matters.

The useful measurement is cheap, because the framework's mode list for a
DisplayPort sink is generally filtered by what the link can carry, not just by
what the monitor's EDID claims. So:

- plug the dock with the unlock **off** and record `modes:<id>` for the HDMI
  display
- compare against the same monitor on the direct cable

If the dock's list is missing the high-rate entries, that is the link limit —
measured, not inferred. If the list is identical, the link is fine and the
cause is elsewhere.

### Two guards added because of this

- **`auto:<hz>`** — a ceiling for auto mode. Caps at or above the ceiling are
  left in place, so a cap matching a real link limit survives while merely
  policy-driven ones are cleared. Useful whenever a link's capability is
  unknown, which is currently the case for the dock.
- **A watchdog.** If a display disappears within 40 seconds of the selection
  changing, the caps are restored automatically and the reason is logged. Being
  left with no picture and no obvious cause is a bad place to put someone.
  Bounded to a short window after a change, so unplugging a cable later is not
  mistaken for it.

### Deciding it

1. Turn the unlock off (app → *Turn unlock off*, or
   `setprop persist.dexrr.unlock ""`), reboot, try the dock.
   - Dock works again → the unlock caused it.
   - Dock still dead → not this module; the earlier working state is restored
     either way.
2. With the unlock off, plug the dock and check **Show display connect/disconnect
   events** in the app.
   - No `DISPLAY-EVENT` at all → Android never saw a display. The link never came
     up, and nothing in software is involved.
   - A device appears and then goes → the framework rejected it, and the mode
     list recorded for it is the next thing to read.
3. Compare the monitor's advertised mode list through the dock against the direct
   cable (`why` prints it with resolutions). If the 144 entry is absent through
   the dock, that is the bandwidth answer, measured rather than assumed.

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

## Two refresh rates, and only one of them has been measured

The monitor's own OSD reported 144 Hz while the content on it was moving at
60 fps. That is not the monitor lying and it is not a contradiction — they are
two different numbers, and this project had only ever reported one of them.

- **Physical refresh rate** — how fast the panel scans. This is the display
  *mode*: `87 @ 144`. It is what the monitor's OSD reads out, because it is the
  only thing the monitor can see.
- **Render frame rate** — how fast Android produces content into that panel.
  A 144 Hz scan-out showing a 60 fps render is 144 Hz of hardware doing 60 Hz
  of work, and every second frame onward is a repeat.

Android votes on the two separately, and has since the vote types were split:

| vote class | constrains |
| --- | --- |
| `RefreshRateVote$PhysicalVote(min,max)` | the mode the panel scans at |
| `RefreshRateVote$RenderVote(min,max)` | the rate content is produced at |

Both extend the abstract `RefreshRateVote`, which is where `mMinRefreshRate`
and `mMaxRefreshRate` actually live — the subclass carries no fields of its
own. So the *only* thing distinguishing a physical cap from a render cap is the
class name. Reflection that reads `mMaxRefreshRate` and stops there, which is
what this module did, cannot tell them apart at all.

That is why `d9:13 RenderVote(0,120)` was listed next to `d9:10
PhysicalVote(0,120)` in the constraint set as though they were the same kind of
thing. They are not: dropping only the physical one would have raised the OSD
number to 144 and changed nothing about how the display actually looked.

### What was measured, and what was not

Every rate quoted anywhere above this section is the **physical** one. It comes
from the mode list and `DisplayInfo`'s active mode. The render rate was never
read, so:

- "120 Hz on both panels" (run 4) means both panels *scan* at 120. Whether
  content was produced at 120 is unknown for that run.
- The user's GL gears observation — OSD at 144, motion at 120 — is the same
  split showing up by eye, and is consistent with `RenderVote(0,120)` standing
  while the physical cap was gone.

### Changes made because of this

- `DisplayInfo.renderFrameRate` is now recorded per display as `render:<id>`,
  and `refreshRateOverride` as `override:<id>` when non-zero. The override is
  the per-app frame-rate override path; a non-zero value there means the rate
  is being set for one app rather than for the display.
- `STATE` lines carry the render rate: `9=120/144r60` reads *display 9, active
  120, max 144, content at 60*.
- `why` reports the two separately — "panel scans at" and "content produced
  at" — and lists the votes limiting each under its own heading. A vote holding
  both appears under both, and the generated `unlock` spec lists it once.
- `Votes.capsPhysicalBelow` / `capsRenderBelow` filter by vote class name;
  `capsBelow` without a kind keeps the old any-cap behaviour, which is what
  `auto` uses, so auto-unlock already drops render caps and needs no change.

### What this does not explain

If the render rate turns out to be at the maximum and motion still looks like
60, the vote table is exhausted and the limit is downstream of it — the
compositor, the app's own frame pacing, or a DeX-specific performance cap. That
would be the first thing in this project not visible in `DisplayModeDirector`
at all.

## DeX caps the rate; mirroring does not

Independent of the vote table, on the same cable and the same monitor:

- **mirroring** ran at 120 Hz
- **DeX** ran at 60 Hz

Same hardware, same link, same session — only the mode differs. This is a clean
behavioural confirmation that the 60 Hz cap is a property of DeX and not of the
cable, the dock, the monitor, or the link's bandwidth. It agrees with where the
cap was found: `d-1 p19`, a global `CombinedVote[PhysicalVote(0,60)
DisableRefreshRateSwitchingVote]` that appears with DeX and is what `unlock
-1:19` removes.

It also disposes of the remaining doubt about the withdrawn bandwidth theory. A
link that carries 120 Hz while mirroring carries 120 Hz while in DeX; nothing
about the wire changed when the mode did.

## Three ways a vote caps a rate, and only one was being read

Chasing the render rate turned up a wider hole: `capsBelow` read
`mMaxRefreshRate` and nothing else, so it saw exactly one of the three shapes a
cap comes in.

| shape | vote | how it caps |
| --- | --- | --- |
| a maximum | `PhysicalVote(min,max)`, `RenderVote(min,max)` | `mMaxRefreshRate` |
| a whitelist of rates | `SupportedRefreshRatesVote` | `List<RefreshRates(peak,vsync)>` — the ceiling is the highest peak |
| a whitelist of modes | `SupportedModesVote` | `List<Integer>` of mode ids — the ceiling is whatever the fastest listed mode runs at |

The last two carry no maximum at all, so a check reading `mMaxRefreshRate`
returns false for them however tightly they restrict the display. Both are now
handled: the rate whitelist inside `Votes` (its highest peak *is* its maximum),
the mode whitelist in `Diagnose`, because resolving mode ids to rates needs the
display's mode list and `Votes` does not have one.

A mode pin is all-or-nothing — it cannot be partially relaxed — so `auto` drops
one only when no `auto:<hz>` ceiling was asked for. With a ceiling, honouring it
means leaving the pin alone.

### Two rendering bugs found alongside

- **`nested()` was too greedy.** It returned the first non-empty `List` field
  it found on any vote, by type, because `CombinedVote.mVotes` has no reliable
  name under R8. But `SupportedModesVote.mModeIds` and
  `SupportedRefreshRatesVote.mRefreshRates` are also `List` fields, so both were
  treated as nested votes and rendered as `[Integer Integer]` and
  `[RefreshRates RefreshRates]`. A mode pin restricting the display to 60 Hz
  would have appeared in a report as noise. It now checks that an element
  actually satisfies one of the interfaces the containing vote implements —
  true of `CombinedVote`'s children, false of mode ids and rate pairs, and
  independent of any class name R8 may have rewritten.
- **`BaseModeRefreshRateVote` never showed its rate.** `terse` looked for
  `mBaseModeRefreshRate`; the field is `mAppRequestBaseModeRefreshRate`. It read
  null every time and fell through to printing the bare class name, so a vote
  pinning the base mode to 60 Hz looked like a vote with no content.
  `RequestedRefreshRateVote.mRefreshRate` and
  `DisableRefreshRateSwitchingVote.mDisableRefreshRateSwitching` were unhandled
  for the same reason.

These are covered by a test that runs the real `Votes` class on a desktop JVM
against stand-ins shaped like the framework's vote classes — same field names,
same nesting, same interface relationship. That the stand-ins are not the
framework's own classes is the point: the reflection cannot tell, which is the
property that has to hold on a firmware where R8 has renamed the originals.
