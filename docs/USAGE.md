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

## 1. Confirm there is something to measure

Settings → Display → Motion smoothness must be **Adaptive**, not Standard. On
Standard the panel is 60 Hz by setting and both halves of the diff will look
identical — which is exactly what happened on the first run.

## 2. Raise the log buffer

Boot output scrolls away fast, and the priority table is printed at boot:

```sh
su -c 'logcat -G 64M'
```

(`tools/dex-probe.sh bigbuffer` does the same. It resets on reboot.)

## 3. Baseline snapshot (no DeX)

Phone idle, not docked, not mirroring:

```sh
su -c 'setprop debug.dexrr.cmd "snapshot dex-off"'
```

**Use `setprop`, not `am broadcast`.** Broadcasts proved unreliable here: they
fail outright from a plain Termux shell, and on the first run the receiver had
never registered so even the root-shell broadcast did nothing. The property
channel needs no Context, no boot phase and no receiver — just a root shell.

## 4. Start DeX, then snapshot again

Dock or start DeX, let it settle for a few seconds, then:

```sh
su -c 'setprop debug.dexrr.cmd "snapshot dex-on"'
```

Repeating a label needs the value to change, so append anything:
`"snapshot dex-on 2"`. `tools/dex-probe.sh snapshot dex-on` handles that for
you.

Label by hand even though the module also snapshots automatically on display
events: automatic DeX detection is guesswork until we know which signal this
firmware updates, and the whole method depends on the two sides of the diff
being correctly identified.

For a quicker, less noisy capture of just the vote table:

```sh
su -c 'setprop debug.dexrr.cmd "votes dex-on"'
```

## 5. Pull the log

```sh
su -c "logcat -d -s DexRRProbe:V" > /sdcard/dexprobe.txt
```

Or read it in LSPosed → Logs → Modules, which needs no shell at all.

To watch live while docking:

```sh
su -c "logcat -s DexRRProbe:V"
```

## 6. Read the diff

Compare the two `SNAPSHOT` blocks. In order of how directly they answer the
question:

1. **`[votes]`** — the vote table, per display and priority. This is the payload.
   A vote that is absent in `dex-off` and present in `dex-on`, or one whose
   range narrows between them, is the thing capping the panel. Note the priority
   number *and* its name.

   Look especially for whichever vote holds the **maximum** down. Run 1 found
   `restrictHighRefreshRate` raising the floor to 60 (min=60, max=Infinity) —
   that is LTPO idle prevention, not the cap. The ceiling was already 60 in
   both states and never changed, so only a full table dump will show it.
2. **`[displays]`** — `supportedModes` for each display. If the 144 Hz mode is
   missing from the list under DeX (rather than present but unselected), the
   filtering happens earlier, when the mode list is built, and the `MODE
   new Display.Mode(...)` lines are the ones to read.
3. **`[last-observed]`** — the most recent value seen at each hooked choke
   point, including what was committed to `SurfaceControl`.

Also scan the running log between the two snapshots for:

- `VOTE ... -> ...   [was ...]` — a vote transition, with its previous value.
  These are printed only when something actually changes, so every line matters.
- `RESTRICT-HRR restrictHighRefreshRate(true)` followed by a **caller stack** —
  Samsung's own restrictor. The stack says which subsystem asked for it, which
  is what ties it to DeX or rules it out.
- `CALL SurfaceControl#setDesiredDisplayModeSpecs(...)` — what was actually
  pushed to SurfaceFlinger.
- `DISPLAY-EVENT ...` — display add/remove, i.e. the DeX session starting.

## 7. Class and field discovery

When something did not resolve, or a Samsung class shows up that we have no
names for:

```sh
# walk the live DisplayManagerService object graph and report every
# display/Samsung class it reaches, with the field path that got there
su -c 'setprop debug.dexrr.cmd "scout"'

# full field + method inventory of one class
su -c 'setprop debug.dexrr.cmd "class com.android.server.display.mode.Vote"'
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
