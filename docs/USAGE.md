# Using the probe

Everything here works from an on-device root shell (Termux). ADB is only
suggested where it is genuinely the better tool — see the last section.

## 0. Confirm the module loaded

Open the **DeX Refresh Probe** app. It reports whether the hook announced itself
in `system_server`.

That check is best-effort: it relies on the hook setting a system property, and
SELinux refuses that on some builds. If it says it could not tell, check
LSPosed → Logs → Modules instead and look for:

```
DexRRProbe: DeX Refresh Probe 0.1.0-probe - attached to system_server
```

Right after that line the module prints three things worth reading before you do
anything else:

- **the vote priority table for this firmware** — the authoritative numbering,
  harvested from the device's own `Vote` class
- **the `SurfaceControl` method inventory** — this is where a Samsung-specific
  refresh-rate entry point would show up
- **the class presence report** — which of the classes we guessed at actually
  exist on One UI 7, including which generation of the vote system it runs
  (Android 15 splits `Vote` into one class per vote kind)

## 1. Baseline snapshot (no DeX)

Phone idle, not docked, not mirroring:

```sh
su -c "am broadcast -a com.tiimoo.dexrefresh.ACTION_SNAPSHOT --es label dex-off"
```

## 2. Start DeX, then snapshot again

Dock or start DeX, let it settle for a few seconds, then:

```sh
su -c "am broadcast -a com.tiimoo.dexrefresh.ACTION_SNAPSHOT --es label dex-on"
```

Label the snapshots by hand like this even though the module also snapshots
automatically on display add/remove. Automatic DeX detection is guesswork until
we know which signal this firmware updates; your label is ground truth, and the
whole method depends on the two sides of the diff being correctly identified.

Extra options:

```sh
# deeper object dump (default 4)
--ei depth 6
# also call getDesiredDisplayModeSpecs() rather than only reading fields
--ez getters true
```

`getters` is off by default: those calls take mode-director locks, and while
that is safe from the snapshot thread it is one more way to perturb the thing
you are trying to observe.

## 3. Pull the log

```sh
su -c "logcat -d -s DexRRProbe:V" > /sdcard/dexprobe.txt
```

Or read it in LSPosed → Logs → Modules, which needs no shell at all.

To watch live while docking:

```sh
su -c "logcat -s DexRRProbe:V"
```

## 4. Read the diff

Compare the two `SNAPSHOT` blocks. In order of how directly they answer the
question:

1. **`[votes]`** — the vote table, per display and priority. This is the payload.
   A vote that is absent in `dex-off` and present in `dex-on`, or one whose
   range narrows between them, is the thing capping the panel. Note the priority
   number *and* its name.
2. **`[displays]`** — `supportedModes` for each display. If the 144 Hz mode is
   missing from the list under DeX (rather than present but unselected), the
   filtering happens earlier, when the mode list is built, and the `MODE
   new Display.Mode(...)` lines are the ones to read.
3. **`[last-observed]`** — the most recent value seen at each hooked choke
   point, including what was committed to `SurfaceControl`.

Also scan the running log between the two snapshots for:

- `VOTE ... -> ...   [was ...]` — a vote transition, with its previous value.
  These are printed only when something actually changes, so every line matters.
- `CALL SurfaceControl#setDesiredDisplayModeSpecs(...)` — what was actually
  pushed to SurfaceFlinger.
- `DISPLAY-EVENT ...` — display add/remove, i.e. the DeX session starting.

## 5. Class and field discovery

When something did not resolve, or a Samsung class shows up that we have no
names for:

```sh
# walk the live DisplayManagerService object graph and report every
# display/Samsung class it reaches, with the field path that got there
su -c "am broadcast -a com.tiimoo.dexrefresh.ACTION_SCOUT --ez scan true"

# full field + method inventory of one class
su -c "am broadcast -a com.tiimoo.dexrefresh.ACTION_SCOUT \
  --es class com.android.server.display.mode.DisplayModeDirector"
```

The graph scan is the one that finds names nobody guessed: whatever Samsung
inserted into the display pipeline is reachable from `DisplayManagerService`,
whatever they called it.

## When ADB is worth it

Prefer the on-device route above for everything routine. ADB earns its place in
two cases:

- **Capturing a long logcat across a dock/undock cycle**, where you want the
  buffer streaming to a file on a machine rather than filling `/sdcard`:
  `adb logcat -s DexRRProbe:V | tee dexprobe.txt`
- **Recovering from a bad boot**, where `adb shell` may be the only way in.

`tools/capture-probe-log.sh` (ADB) and `tools/dex-probe.sh` (on-device) wrap the
common sequences.

## Turning it off

LSPosed → Modules → DeX Refresh Probe → disable → reboot. It shares no state
with any other module and writes nothing outside logcat.
