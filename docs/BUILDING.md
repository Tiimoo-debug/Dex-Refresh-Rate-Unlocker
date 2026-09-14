# Building the APK

No APK is committed to this repo, and there is no PC with an Android SDK
available, so there are two routes. They produce the same module.

---

## Route 1 — GitHub Actions (recommended)

Zero local setup, nothing installed on the phone, and it is the route most
likely to just work.

1. Push the branch (it is already pushed). The workflow in
   `.github/workflows/build.yml` runs on every push.
2. On the phone, open the repo on github.com → **Actions** tab → the most
   recent **Build probe APK** run.
3. Scroll to **Artifacts** and download `dex-refresh-probe-apk`. It is a zip;
   any Android file manager will open it.
4. Install the `.apk` inside.

You can also start a build by hand: Actions → Build probe APK → **Run
workflow**.

The APK is signed with the standard Android debug key, which is all LSPosed
needs.

> If the Actions tab shows nothing, workflows may be disabled for the
> repository — Settings → Actions → General → *Allow all actions*.

---

## Route 2 — Termux, entirely on the phone

`tools/termux-build.sh` builds without Gradle or an Android SDK.

Gradle and AGP are not usable here: they shell out to `aapt2`, which Google
ships only as an x86_64 Linux binary and which will not run on an ARM phone.
The script uses the plain `javac` → dexer → `aapt` → `apksigner` pipeline
instead, all of which Termux can supply.

```sh
pkg install openjdk-17 aapt apksigner dx curl
git clone https://github.com/Tiimoo-debug/Dex-Refresh-Rate-Unlocker
cd Dex-Refresh-Rate-Unlocker
./tools/termux-build.sh
```

Output: `build-termux/dex-refresh-probe.apk`.

**What it downloads.** On first run it fetches `org.robolectric:android-all`
(~186 MB) from Maven Central. That one jar carries both the `android.*` classes
`javac` needs and the `resources.arsc` that `aapt` needs, so it stands in for
`android.jar`. If you already have an `android.jar`, skip the download with:

```sh
ANDROID_JAR=/path/to/android.jar ./tools/termux-build.sh
```

**Caveats, stated plainly.** This route has not been run end to end — it was
written in an environment with no Termux and no Android build tools. The compile
stage was simulated and verified; the `aapt`, dex and signing stages were not.
Two things could bite:

- **`aapt` may not be in your Termux repos.** It has moved between repos over
  the years. The script preflights for it and tells you up front rather than
  failing halfway. If it is genuinely unavailable, use Route 1 — there is no
  good workaround, since a real APK needs a binary `AndroidManifest.xml` and
  only `aapt`/`aapt2` produces one.
- **The dexer may be `d8` or `dx`.** The script accepts either. The sources are
  deliberately free of lambdas and method references so that even plain `dx`,
  which cannot desugar `invokedynamic`, can handle them. This is verified: the
  compiled bytecode contains no `invokedynamic` instructions.

---

## Route 3 — a PC, if one turns up

```sh
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and an Android SDK with API 35. Android Studio works too: open the
project root. Nothing is fetched from `api.xposed.info` — the Xposed API is
vendored as compile-only stubs in `xposed-stubs/`.

---

## After installing, whichever route

1. LSPosed → Modules → **DeX Refresh Probe** → enable.
2. Scope: tick **System Framework** (`android`) only, so this cannot interact
   with AFWall+/Fyrypt, microG/Play Integrity, Core Patch or anything else
   already on the device.
3. Reboot.
4. See [USAGE.md](USAGE.md).
