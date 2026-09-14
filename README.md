# DeX Refresh Rate Unlocker — Phase 1: probe

An LSPosed/Xposed module for investigating why Samsung DeX caps the refresh rate
below what the panel supports.

**Target device:** Galaxy S22 Ultra (SM-S908B), One UI 7 / **Android 15**
(API 35), Exynos 2200, rooted with Magisk, LSPosed installed.

**This build changes nothing.** It is observation only: it hooks the display and
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

## Using it

Full workflow in **[docs/USAGE.md](docs/USAGE.md)**. The short version, from a
Termux root shell — no ADB needed:

```sh
su -c "am broadcast -a com.tiimoo.dexrefresh.ACTION_SNAPSHOT --es label dex-off"
# start DeX, wait for it to settle
su -c "am broadcast -a com.tiimoo.dexrefresh.ACTION_SNAPSHOT --es label dex-on"
su -c "logcat -d -s DexRRProbe:V > /sdcard/dexprobe.txt"
```

Then diff the two snapshots. The vote that appears or tightens between them is
what caps the panel.

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
