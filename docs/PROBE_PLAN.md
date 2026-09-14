# What is hooked, why, and what comes next

## The hypothesis

On Android 15, `DisplayModeDirector` decides the allowed refresh-rate range for
each display by collecting `Vote`s. Each vote is filed under a priority; the
final range is their intersection, and higher priorities win outright above a
cutoff. Two entry points file votes:

```java
// com.android.server.display.mode.VotesStorage  (verified, AOSP 15 and 14)
void updateVote(int displayId, int priority, Vote vote);
void updateGlobalVote(int priority, Vote vote);      // displayId == -1
```

If DeX caps the panel through the framework at all, it has to show up as a vote
appearing or tightening when the DeX session starts. That makes `updateVote` /
`updateGlobalVote` the highest-value observation point in the system, and the
before/after vote table the primary deliverable of this pass.

If the vote table looks identical with and without DeX, the cap is not being
applied through the mode director, and the next suspects in order are: the mode
list itself (`Display$Mode` construction), the commit to the display device
(`setDesiredDisplayModeSpecsLocked`), a Samsung-specific `SurfaceControl` call,
or the DeX apps requesting it for their own windows.

## Verification already done

This module was written without an Android SDK available, so its assumptions
were checked against real framework jars (`org.robolectric:android-all`, which
ship the actual `com.android.server.*` classes) rather than from memory. Both
**Android 15 / API 35** (`15-robolectric-13954326`, the real target) and
Android 14 were checked, because the vote system changed between them:

- The full source set typechecks against both.
- The discovery logic was dry-run against real bytecode from both: which methods
  each regex selects, how each vote entry point is classified, and whether the
  priority-constant harvest works.

That dry-run caught five real bugs, all fixed:

1. `SurfaceControl` has `private static native nativeSetDesiredDisplayModeSpecs`,
   which matched the hook regex. Hooking a JNI native is unsupported and would
   have perturbed the exact path being observed — and broken the
   observation-only guarantee. Native and abstract methods are now never hooked.
2. `updateGlobalVote(int priority, Vote)` has a different shape from
   `updateVote(int, int, Vote)`. The original code would have read its
   arguments off by one position and mislabelled every global vote — the votes
   most likely to carry a panel-wide cap.
3. The snapshot trigger regex missed `onDisplayDeviceEventLocked` and
   `onDisplayDeviceChangedLocked`, the methods the framework actually uses.
   Automatic snapshots would never have fired.
4. The hook regex missed `updateDisplayModesLocked` and
   `requestDisplayStateInternal`.
5. The whole thing was originally aimed at Android 14. One UI 7 is **Android
   15**, where `Vote` stopped being a concrete class and became an interface
   with one implementation per vote kind, and where the priority numbering
   shifted by up to six places. Re-verifying against API 35 is what surfaced
   the priority table below.

Because everything resolves reflectively with per-name fallbacks, the module
works against either generation; the Android 15 numbers are the ones that
matter here.

## AOSP 15 vote priority baseline

Harvested from the API 35 framework jar. Diff this against what the module
prints at boot — the difference is Samsung's.

```
  0 = PRIORITY_DEFAULT_RENDER_FRAME_RATE
  1 = PRIORITY_FLICKER_REFRESH_RATE
  2 = PRIORITY_HIGH_BRIGHTNESS_MODE
  3 = PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE
  4 = PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE
  5 = PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE
  6 = PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE
  7 = PRIORITY_APP_REQUEST_SIZE
  8 = PRIORITY_USER_SETTING_PEAK_REFRESH_RATE
  9 = PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE
 10 = PRIORITY_SYNCHRONIZED_REFRESH_RATE
 11 = PRIORITY_LIMIT_MODE
 12 = PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE
 13 = PRIORITY_LAYOUT_LIMITED_FRAME_RATE
 14 = PRIORITY_SYSTEM_REQUESTED_MODES
 15 = PRIORITY_LOW_POWER_MODE_MODES
 16 = PRIORITY_LOW_POWER_MODE_RENDER_RATE
 17 = PRIORITY_FLICKER_REFRESH_RATE_SWITCH
 18 = PRIORITY_SKIN_TEMPERATURE
 19 = PRIORITY_PROXIMITY
 20 = PRIORITY_UDFPS
      MIN_PRIORITY = 0, MAX_PRIORITY = 20
      APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF = 5
```

On Android 15 a `Vote` is an interface, and each kind is its own class:
`RefreshRateVote$PhysicalVote`, `RefreshRateVote$RenderVote`, `SizeVote`,
`BaseModeRefreshRateVote`, `DisableRefreshRateSwitchingVote`,
`SupportedModesVote`, `SupportedRefreshRatesVote`, `RequestedRefreshRateVote`,
`CombinedVote`. The class name alone tells you what kind of restriction a vote
is, which makes the log considerably easier to read than on Android 14.

Two are worth watching specifically, because they can remove modes rather than
merely narrow a range: `SupportedModesVote` (a literal allow-list of mode ids)
and `PRIORITY_SYSTEM_REQUESTED_MODES`, fed by
`DisplayModeDirector.requestDisplayModes(IBinder, int, int[])` — an Android 15
addition and an obvious way for a DeX service to restrict the panel.

### Why LibreDeX's numbers do not transfer

LibreDeX drops priorities **15** and **22**, which on their Android 16 / One UI 8
target mean `PRIORITY_SYNCHRONIZED_REFRESH_RATE` and
`PRIORITY_LOW_POWER_MODE_MODES`. On Android 15 those two concepts are numbered
**10** and **15**, and `MAX_PRIORITY` is 20.

So copying their constants here would drop priority 15 — which on this device is
`PRIORITY_LOW_POWER_MODE_MODES`, the right idea but reached by accident and not
the vote they meant by 15 — and priority 22, which does not exist at all and
would silently do nothing. That is exactly why the module reads the table off
the device instead of hardcoding it.

The *names* are worth carrying over as hypotheses even though the numbers are
not: `PRIORITY_SYNCHRONIZED_REFRESH_RATE` and `PRIORITY_LOW_POWER_MODE_MODES`
were the culprits on One UI 8, and both exist on Android 15. Check them first in
the diff — but confirm from the log rather than assuming.

Samsung may add priorities above 20. Any priority the module reports that is not
in the list above is a Samsung addition and is immediately interesting.

## Hook inventory

Resolved by reflection; anything absent is logged and skipped.

**Vote path (the primary target)**
- `VotesStorage.updateVote` / `updateGlobalVote` / `removeVote` / `removeAllVotes`
- fallback for the pre-Android-14 layout: `DisplayModeDirector.updateVoteLocked`

**Decision**
- `DisplayModeDirector.getDesiredDisplayModeSpecs(int)`, `getMaxRefreshRateLocked`,
  `notifyDesiredDisplayModeSpecsChangedLocked`, and on Android 15
  `requestDisplayModes(IBinder, int, int[])`
- `DisplayManagerService.requestDisplayPower(int, boolean)` — present on
  Android 15, and the method LibreDeX reflects into

**Commit**
- `LocalDisplayAdapter$LocalDisplayDevice.setDesiredDisplayModeSpecsLocked`,
  `setDesiredDisplayModeSpecsAsync`, `updateDisplayModesLocked`,
  `updateActiveModeLocked`, `onActiveDisplayModeChangedLocked`
- `SurfaceControl.setDesiredDisplayModeSpecs` / `getDesiredDisplayModeSpecs`

**Mode list**
- all `android.view.Display$Mode` constructors

**Session boundaries**
- `LogicalDisplayMapper.onDisplayDeviceEventLocked` / `onDisplayDeviceChangedLocked`
  / `handleDisplayDeviceAddedLocked` / `handleDisplayDeviceRemovedLocked`

**Samsung, discovered not assumed**
- the full `SurfaceControl` method inventory is printed at boot, which is where
  a One UI 7 analogue of One UI 8's `notifyHFRmode` would appear
- `ACTION_SCOUT`'s graph scan walks the live `DisplayManagerService` and reports
  every Samsung/display class reachable from it, with the field path

## Phase 2 — only after the log says where

Once the log identifies the filtering point, extend the module to neutralise it
*only while a DeX session is active*, following DispUnlock's approach to the
generic external-display cap but aimed at DeX's path.

Preconditions before writing a single line of phase 2:

1. A confirmed choke point, named from this device's own log — not inferred from
   AOSP and not copied from LibreDeX.
2. A reliable "DeX is active" signal. The probe currently only *guesses* at
   this (`DeXState`); the candidate properties and broadcasts it lists are
   unverified, and the log will show which, if any, actually fire. A modification
   that cannot tell when DeX is running would cap or uncap at the wrong times.
3. A decision about whether the change belongs at the vote level (cleanest:
   suppress or widen one vote) or at the commit level (blunter, but works if the
   cap is applied below the mode director).

Deliberately out of scope, now and later: WindowManager task/display-tree
manipulation. LibreDeX needs it for a different problem; it has nothing to do
with refresh rate.
