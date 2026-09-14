# DeX Refresh Rate Unlocker — Phase 1: probe

An LSPosed/Xposed module for investigating why Samsung DeX caps the refresh rate
below what the panel supports.

**Target device:** Galaxy S22 Ultra (SM-S908B), One UI 7 / **Android 15**
(API 35), Exynos 2200, rooted with Magisk, LSPosed installed.

**Observation only by default.** Phase 2 adds opt-in vote suppression, inert
until you explicitly name votes to drop; with nothing configured the module
changes nothing: it hooks the display and
refresh-rate decision points inside `system_server` and logs what passes through
them. No hook calls `setResult`, replaces an argument, or writes a field. The
point of this pass is to find out *where* the cap is applied before trying to
remove it — the previous APK-downgrade attempt failed precisely because it
changed things without knowing that.

## Why hook system_server instead of patching the DeX APKs

The three DeX system apps (`desktoplauncher`, `dexsystemui`,
`desktopmode.uiservice`) were downgraded to One UI 5.1 versions in an earlier
attempt. That was inconclusive and destabilised the device. Hooking the
framework instead is reversible (toggle the module off, reboot), scoped, and
observable.

That the panel and GPU pipeline can do 144 Hz is already established: the
unrelated [DispUnlock](https://github.com/zorrobyte/external-display-unlock)
module reaches it over an external monitor in native Android desktop windowing
mode. So the cap is specific to DeX's code path, which is what this module is
pointed at.

## What it hooks

Everything is resolved by reflection at runtime and every lookup is optional —
anything absent is logged as `ABSENT` and the probe carries on.

| Target | Why |
| --- | --- |
| `VotesStorage.updateVote` / `updateGlobalVote` | On Android 15 every constraint on the allowed refresh-rate range is a `Vote` filed under a priority, and the result is the intersection. Whatever drops the panel to 60 Hz under DeX has to appear here. |
| `DisplayModeDirector.getDesiredDisplayModeSpecs`, `requestDisplayModes` | What the framework concluded from those votes, and the Android 15 entry point a system service uses to restrict the panel to a set of mode ids. |
| `LocalDisplayAdapter$LocalDisplayDevice.setDesiredDisplayModeSpecs*` | The last mile before SurfaceFlinger. |
| `SurfaceControl` refresh-rate methods | Ground truth, and the place Samsung adds non-AOSP entry points (One UI 8 has `notifyHFRmode`; the One UI 7 equivalent, if any, is discovered rather than guessed — the full method inventory is printed at boot). |
| `Display$Mode` constructors | The available-modes list being built. A list that shrinks under DeX shows up here. |
| `LogicalDisplayMapper` add/remove/change | Brackets the DeX session and triggers automatic snapshots. |
| `DisplayManagerService` mode/refresh methods | Including `requestDisplayPower` and `requestDisplayStateInternal`. |

Optional, off by default: app-side hooks for the three DeX packages, to see what
DeX itself requests for its own windows. Add them to the module's scope in
LSPosed to enable — see `app/src/main/res/values/arrays.xml`.

## Build

No APK is committed to this repo, and there is no PC with an Android SDK in
play, so there are two ways to get one. See **[docs/BUILDING.md](docs/BUILDING.md)**
for the detail.

**Recommended — GitHub Actions, no local toolchain at all.** Push the branch and
the workflow in `.github/workflows/build.yml` builds the APK; download it from
the run's Artifacts section in the phone's browser. Or trigger it by hand from
the Actions tab.

**On-device — Termux.** `tools/termux-build.sh` drives a plain
`javac` → dexer → `aapt` → `apksigner` build entirely on the phone, with no
Gradle and no SDK (AGP is unusable here: its `aapt2` is an x86_64-only binary).
Its compile stage is verified; the packaging stages are not, and it depends on
`aapt` being available in your Termux repos — so it preflights and tells you
what is missing rather than failing halfway.

Either way the Xposed API is vendored as compile-only stubs in `xposed-stubs/`,
so nothing is fetched from `api.xposed.info`.

> The module has been typechecked against real Android 15 **and** Android 14
> framework jars, and its discovery logic dry-run against actual AOSP bytecode
> from both (see `docs/PROBE_PLAN.md`) — but it has never been compiled to DEX
> or run on a device. Treat the first boot with it enabled accordingly.

## Install

1. Install the APK.
2. LSPosed → Modules → **DeX Refresh Probe** → enable.
3. Scope: tick **System Framework** (`android`) only. Leave everything else
   unticked so this module cannot interact with AFWall+/Fyrypt, microG/Play
   Integrity, Core Patch or anything else already installed.
4. Reboot.
5. Open the **DeX Refresh Probe** app for the on-device command cheat sheet, or
   read `docs/USAGE.md`.

Disable it independently at any time from the LSPosed UI; it shares no state
with other modules.

## Using it — the app

Open **DeX Refresh Probe** from the launcher. Everything the module does is a
button: enable auto unlock, turn it off, apply a custom spec, run the `why`
diagnosis, take a snapshot, scan classes, and read the reports back — with the
result shown in the app and a copy button.

It shells out through `su` to set the properties the hook polls, so Magisk will
ask for root once. Current state (per-display active/max Hz, and whether the
unlock is on) is read from a property with no root needed, so the status panel
works even if you decline.

The shell route below still works and is what the app drives underneath.

## Using it — the shell

Full workflow in **[docs/USAGE.md](docs/USAGE.md)**. The short version, from a
Termux root shell — no ADB needed:

```sh
su -c 'logcat -G 64M'
su -c 'setprop debug.dexrr.cmd "snapshot dex-off"'
# start DeX, wait for it to settle
su -c 'setprop debug.dexrr.cmd "snapshot dex-on"'
su -c 'logcat -d -s DexRRProbe:V' > /sdcard/dexprobe.txt
```

Then diff the two snapshots. The vote that appears or tightens between them is
what caps the panel.

`setprop` rather than `am broadcast`: broadcasts turned out to be unreliable on
this device, so the property channel is the primary trigger. See
[docs/FINDINGS.md](docs/FINDINGS.md).

## What has been found so far

`SurfaceControl.restrictHighRefreshRate(boolean)` exists on One UI 7 and not in
AOSP — Samsung's own refresh-rate restrictor, and the One UI 7 counterpart of
the `notifyHFRmode` call LibreDeX found on One UI 8. It files a vote on display
0 within milliseconds of being called.

It is not the cap we are after, though: the vote raises the floor to 60 Hz
(`min=60, max=Infinity`) rather than lowering the ceiling, which is LTPO idle
prevention. The ceiling was already 60 in both halves of the first capture.
Full write-up, including what went wrong with that run, in
[docs/FINDINGS.md](docs/FINDINGS.md).

## Result

A single global vote — `CombinedVote[PhysicalVote(0,60) DisableRefreshRateSwitchingVote]`
at display -1 — was holding **both** the phone panel and the external monitor to
60 Hz. Dropping it gives 120 Hz on both simultaneously.

```sh
su -c 'setprop persist.dexrr.unlock "auto"'      # survives reboots
```

Going to 144 Hz on the external display needs three more caps dropped, including
a second **global** one at priority 11 that is easy to miss; `auto` finds them
all. See [docs/FINDINGS.md](docs/FINDINGS.md).

## Phase 2: suppressing the cap

Identified, so the module can now act on it — but only when told to, and a
reboot clears it:

```sh
# Recommended: let the module work out what to drop, and keep it correct.
su -c 'setprop persist.dexrr.unlock "auto"'

# Or name the votes yourself, from the why report:
su -c 'setprop debug.dexrr.cmd "why"'                  # what caps each display
su -c 'setprop debug.dexrr.cmd "unlock -1:19,-1:11,*:10,*:13"'
su -c 'setprop debug.dexrr.cmd "unlock off"'
```

**`auto`** asks, for every display, which votes hold it below the fastest mode
it advertises, and drops exactly those — re-checked continuously, so it survives
redocks, display re-creation and reboots without being retyped. Each display is
measured against *its own* maximum, so nothing is dropped for the 120 Hz phone
panel that only the 144 Hz monitor needs.

`*:P` drops priority P on every display. Use it rather than a fixed id: the
HDMI display was observed moving 6 → 7 → 8 → 9 in one session, because it is
re-created on every mode change, so an id-specific rule stops working after a
redock.

It suppresses the exact `display:priority` votes you name, rather than applying
a blanket rule — because R8 stripped the priority constants from this firmware,
so nothing can distinguish a thermal-throttling vote from a DeX one by name, and
a blanket rule would silently disable thermal protection. See
[docs/FINDINGS.md](docs/FINDINGS.md).

## Known risk: docks and link bandwidth

Dropping the caps also removes `DisableRefreshRateSwitchingVote`, which pins a
display to one mode. If a link cannot carry the mode that then becomes
reachable, DisplayPort gives **no picture at all** rather than falling back,
while power and ethernet keep working.

Whether any particular connection is limited that way is a question to measure
rather than assume — compare the mode list the framework reports for the same
monitor on each connection.

If a dock stops showing a display, turn the unlock off first. See
[docs/FINDINGS.md](docs/FINDINGS.md#dock-regression--read-this-before-enabling-the-unlock).

## Safety notes

Hooking `system_server` can brick a boot. What this module does about that:

- Every reflective lookup returns null rather than throwing, and every hook body
  is wrapped so nothing propagates into `system_server`.
- Logging is asynchronous through a bounded queue that drops rather than blocks.
  Hook callbacks frequently run under `DisplayManagerService` locks, and
  blocking there trips the watchdog.
- Nothing that takes a binder call or a server lock runs on a hook thread.
  Snapshots run on their own thread precisely because reading display state
  re-enters `DisplayManagerService`; doing that from a hook already holding its
  lock deadlocks the device.
- Native and abstract methods are never hooked, even when their names match.
- Logging is change-triggered and rate-limited, so a per-frame path cannot flood
  the log.

If a boot loop happens anyway: boot to recovery or use Magisk's safe mode to
disable LSPosed modules, or remove the module's APK. Nothing here writes to
system partitions.

## Not in scope

WindowManager task/display-tree manipulation. LibreDeX documents that as a
separate concern for their own use case; it is irrelevant to refresh rate and
this module does not touch it.

## Credit

Methodology — brute-force reflective field dumping to find real field names
rather than assuming AOSP's, and the finding that refresh-rate caps surface as
mode-director votes — is taken from
[LibreDeX](https://github.com/KanzakiK/libredex-public)'s `ANDROID16_WMS_REFLECT.md`
write-up. **None of their code is used here**, and none of their identifiers are
assumed to apply: they target a Galaxy Z Flip 5 on One UI 8 / Android 16
(Snapdragon), this targets an S22 Ultra on One UI 7 / Android 15 (Exynos). Their
vote priority numbers in particular are wrong for this device — see
`docs/PROBE_PLAN.md`.
