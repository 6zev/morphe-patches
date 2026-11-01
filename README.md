# 🧩 tvQuickActions patches for Morphe

Unofficial [Morphe](https://morphe.software) patch bundle for **tvQuickActions Pro**
(`dev.vodik7.tvquickactions`), a button/key mapper for Android TV, Google TV and AOSP devices.

Not affiliated with the Morphe project or with tvDev. See the [NOTICE](NOTICE).

## ❓ About

tvQuickActions gates its premium features behind a bundled Google LVL fork, and since 3.8.0 it
loads a native engine library from `Application.onCreate` that **aborts the process when the APK
is not signed with the original certificate**. That makes a plain "patch and re-sign" impossible:
the app dies a fraction of a second after launch, before it draws anything.

These patches reproduce what the working 3.7 mod does, at the bytecode level:

1. **Unlock premium** – the licence policy is forced to always report the app as licensed.
2. **Bypass native engine** – the `System.loadLibrary("d")` call in `App.onCreate` is
   neutralised, so the integrity check that aborts the process never runs, and the methods that
   library provided are stubbed so the app cannot die with `UnsatisfiedLinkError`.

### How to use these patches

Add this repo URL in Morphe Manager → patch sources, then patch your own copy of the app:

```
https://github.com/6zev/morphe-patches
```

<a href="https://morphe.software/add-source?github=6zev/morphe-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.0.1](https://github.com/6zev/morphe-patches/releases/tag/v1.0.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;2 patches total
<details open>
<summary>📦 tvQuickActions Pro&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**Package name:** `dev.vodik7.tvquickactions`

**🎯 Supported versions:**

| 3.8.0 | 3.7.0 |
| :---: | :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Skip native engine load](#skip-native-engine-load) | Neutralises the System.loadLibrary("d") call in App.onCreate. That library aborts the process when the APK is not signed with the original certificate, which is what makes every re-signed build crash on startup. The native engine becomes unavailable, so features that depend on it (web widget overlay, web style button emulation) stop working. |  |
| [Unlock premium](#unlock-premium) | Makes the bundled Google LVL policy always report the app as licensed, which unlocks the premium features. |  |

</details>

<!-- PATCHES_END -->

## 📱 Supported versions

| App version | Status |
|---|---|
| 3.8.0 | ✅ developed and tested against this release |
| 3.7.0 | ⚠️ expected to work (same licence policy, no engine load at all) |

Get the **original, unmodified APK** (ApkMirror, UpToDown or the Play Store) — do not patch an
APK that was already modded, its fingerprints will not match.

## ⚠️ Notes and caveats

- Morphe signs the patched APK with its own key, so you must **uninstall the original app first**.
  App settings are lost unless the app ships its own backup/export.
- The native engine is not available, so features that depend on it do not work: the
  **Web Widget Overlay** and web style **button emulation**. Everything else (key remapping,
  ADB/Shizuku, macros, settings) behaves normally.
- Root is **not** required.
- A restored backup can carry a trigger or mapping that runs **Stop recent apps**, which
  kills every app in the recents list over ADB - including tvQuickActions itself. On 3.8
  that makes the app restart every few seconds (`Force stopping dev.vodik7.tvquickactions`).
  Remove that action from the trigger, or clear the app's data and set it up again.
- If the app is updated, the patches may need adjustments: class and method names are obfuscated
  differently in every release. The patches anchor on data that does not change (the LVL
  preferences file name, the unobfuscated `App` class), which should keep them working across
  releases, but a new app version always deserves a test run.

## 🚀 Development

Requires JDK 21 and a GitHub Personal Access Token (classic) with the `read:packages` scope,
exported as `GITHUB_TOKEN`, plus `gpr.user`/`gpr.key` in `~/.gradle/gradle.properties`
(see the [patcher setup guide](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md)).
The Morphe Gradle plugin is hosted on GitHub Packages and cannot be resolved anonymously.

```sh
./gradlew :patches:buildAndroid
```

The built bundle lands in `patches/build/libs/patches-*.mpp`. Apply it locally with
[Morphe Desktop](https://github.com/MorpheApp/morphe-desktop):

```sh
java -jar morphe-desktop-*-all.jar patch -p patches-*.mpp tvquickactions.apk
```

### Layout

```
patches/src/main/kotlin/app/tvqa/patches/
├── shared/Constants.kt                     # app compatibility (package, versions)
└── tvquickactions/
    ├── Fingerprints.kt                     # App.onCreate / System.loadLibrary
    ├── UnlockPremiumPatch.kt               # licence policy -> allowAccess() = true
    └── SkipNativeEngineLoadPatch.kt        # drop the native engine load
```

The extension module is unused: both patches are pure bytecode patches.

## 📜 License

Licensed under the [GNU General Public License v3.0](LICENSE).
