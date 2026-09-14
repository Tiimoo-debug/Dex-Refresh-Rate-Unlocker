# What is hooked, why, and what comes next

## The hypothesis

On Android 14, `DisplayModeDirector` decides the allowed refresh-rate range for
each display by collecting `Vote`s. Each vote is filed under a priority; the
final range is their intersection, and higher priorities win outright above a
cutoff. Two entry points file votes:

```java
// com.android.server.display.mode.VotesStorage  (verified, AOSP 14)
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
were checked against a real Android 14 framework jar
(`org.robolectric:android-all:14-robolectric-10818077`, which ships the actual
`com.android.server.*` classes) rather than from memory:

- The full source set typechecks against Android 14.
- The discovery logic was dry-run against real AOSP 14 bytecode: which methods
  each regex selects, how each vote entry point is classified, and whether the
  priority-constant harvest works.

That dry-run caught four real bugs, all fixed:

1. `SurfaceControl` has `private static native nativeSetDesiredDisplayModeSpecs`,
   which matched the hook regex. Hooking a JNI native is unsupported and would
   have perturbed the exact path being observed — and broken the
   observation-only guarantee. Native and abstract methods are now never hooked.
2. `updateGlobalVote(int priority, Vote)` has a different shape from
   `updateVote(int, int, Vote)`. The original code would have read its
   arguments off by one position and mislabelled every global vote — the votes
   most likely to carry a panel-wide cap.
3. The snapshot trigger regex missed `onDisplayDeviceEventLocked` and
   `onDisplayDeviceChangedLocked`, the methods AOSP 14 actually uses. Automatic
   snapshots would never have fired.
4. The hook regex missed `updateDisplayModesLocked` and Android 14's
   `requestDisplayStateInternal` (the modern name for `requestDisplayPower`).

**This is not the same as having run on the device.** AOSP 14 is the baseline
One UI 7 diverges *from*; the point of the probe is to measure that divergence.

## AOSP 14 vote priority baseline

Harvested from the framework jar. Diff this against what the module prints at
boot — the difference is Samsung's.

```
  0 = PRIORITY_DEFAULT_RENDER_FRAME_RATE
  1 = PRIORITY_FLICKER_REFRESH_RATE
  2 = PRIORITY_HIGH_BRIGHTNESS_MODE
  3 = PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE
  4 = PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE
  5 = PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE
  6 = PRIORITY_APP_REQUEST_SIZE
  7 = PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE
  8 = PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE
  9 = PRIORITY_LAYOUT_LIMITED_FRAME_RATE
 10 = PRIORITY_LOW_POWER_MODE
 11 = PRIORITY_FLICKER_REFRESH_RATE_SWITCH
 12 = PRIORITY_SKIN_TEMPERATURE
 13 = PRIORITY_PROXIMITY
 14 = PRIORITY_UDFPS
      MIN_PRIORITY = 0, MAX_PRIORITY = 14
      APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF = 4
```

### Why LibreDeX's numbers do not transfer

LibreDeX drops priorities **15** (`PRIORITY_SYNCHRONIZED_REFRESH_RATE`) and
**22** (`PRIORITY_LOW_POWER_MODE_MODES`). Neither exists in Android 14, where
the range stops at 14. Copying those constants here would drop whatever One UI 7
happens to have numbered 15 and 22 — if anything — which is why the module reads
the table off the device instead of hardcoding it.

Samsung very likely adds priorities above 14. Any priority the module reports
that is not in the list above is a Samsung addition and is immediately
interesting.

## Hook inventory

Resolved by reflection; anything absent is logged and skipped.

**Vote path (the primary target)**
- `VotesStorage.updateVote` / `updateGlobalVote` / `removeVote` / `removeAllVotes`
- fallback for a pre-Android-14 layout: `DisplayModeDirector.updateVoteLocked`

**Decision**
- `DisplayModeDirector.getDesiredDisplayModeSpecs(int)`, `selectBaseMode`,
  `getMaxRefreshRateLocked`, `notifyDesiredDisplayModeSpecsChangedLocked`

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
