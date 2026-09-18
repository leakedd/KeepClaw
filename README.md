# KeepClaw

**Android host for [picoclaw](https://github.com/sipeed/picoclaw): the agent gateway and its local web console
run inside a foreground service — persistently, like a VPN or a music player. No Termux, no root, no ADB in
daily use.**

<p align="center">
  <a href="https://github.com/leakedd/KeepClaw/releases/download/v0.1.0/KeepClaw-0.1.0-release.apk">
    <img alt="Download the APK" src="https://img.shields.io/badge/DOWNLOAD_APK-60_MB_%C2%B7_arm64--v8a-2ea44f?style=for-the-badge&logo=android&logoColor=white" height="44">
  </a>
  &nbsp;
  <a href="https://github.com/leakedd/KeepClaw/releases/latest">
    <img alt="All releases" src="https://img.shields.io/badge/All_releases-v0.1.0_early_preview-orange?style=for-the-badge" height="44">
  </a>
</p>

<p align="center">
  <b>Android 8+ · arm64-v8a · sideload, self-signed · no account, no Play Store, no telemetry</b>
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white">
  <img alt="ABI arm64-v8a" src="https://img.shields.io/badge/ABI-arm64--v8a-informational">
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue">
  <img alt="Status early preview" src="https://img.shields.io/badge/status-early_preview-orange">
  <img alt="Engine: picoclaw bbf6893c" src="https://img.shields.io/badge/engine-picoclaw%40bbf6893c-lightgrey">
</p>

---

## Why not Termux

Termux runs the binary inside an app sandbox: the moment Android freezes or kills Termux (locked screen, memory
pressure, reboot), **sshd, the CLI and the gateway die with it**. `termux-wake-lock` does not change that.
KeepClaw implements the Android contract that lets a program live:

| Clause | Implementation |
|---|---|
| Stay awake | `ClawService`, foreground service (`specialUse`) + non-dismissible persistent notification |
| Survive the swipe | `android:stopWithTask="false"` + `START_STICKY` + `onTaskRemoved` |
| Come back after reboot | `BootReceiver` (`BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`) |
| Not be dozed | Battery-optimization exemption, one tap from the app (🔋) |
| Kill it by hand | **Stop** action in the notification (visible on the lock screen) and a quick-settings tile |

The UI is a WebView on the local console (`127.0.0.1:18800`); the agent itself talks through channels
(Telegram, Discord, …).

## Install

Requires **Android 8.0+** and an **arm64-v8a** device. The APK is self-signed: Android will warn about an
unknown source — expected for a sideloaded app.

```bash
adb install -r KeepClaw-0.1.0-release.apk
```

Verify what you downloaded before installing:

```
APK   47013f9c91904bf060ac1791d64df4bc0ebdc550e58f2a6bb737fe1199c52d4b
SRC   38ec6b46d54a4a574ee3a2365720f19b6fd52acb2fc9ead04fa8c0268ebbf640
```

```bash
shasum -a 256 -c SHA256SUMS.txt      # macOS
sha256sum -c SHA256SUMS.txt          # Linux
apksigner verify --print-certs KeepClaw-0.1.0-release.apk
# Signer #1 certificate SHA-256:
# 6a0929eeb563a43abdbb5f3a75d09f5149c56ca2f807bb6322ac4818ace0ce02
```

First run: tap **🔋** once (battery exemption) → open the console → create the console password → add a model
in **Models → Providers** (any OpenAI-compatible endpoint, plus a first-class OpenCode Go provider and a
free-form **Custom** provider). Keys live in the app's private `.security.yml` (0600) — never in the APK.
Console and API are bound to **127.0.0.1**: nothing is exposed on the LAN.

## Status

Early preview, **not finished** — APIs, UI and packaging will change without notice.

| Works | Not done |
|---|---|
| Foreground service, persistent notification, quick-settings tile (verified on Android 16) | Final design pass, onboarding, empty states, in-app home screen |
| Local console with mobile layout patches; persistent session (no re-login after reboot) | Channels (Telegram / Discord) validated end-to-end from the app |
| Gateway lifecycle: no orphans, adoption of a healthy gateway via `/health` | Screen-off soak test not certified |
| Providers tab: API key → model fetch → bulk add, per-model **In chat** switch | `armeabi-v7a` / `x86_64` (arm64-v8a only) |
| DNS resolution on Android, battery exemption, reset, restart after boot | i18n, wake word, widget, log export |

## Build from source

```bash
brew install go gradle openjdk@21
brew install --cask android-commandlinetools
sdkmanager --sdk_root=$HOME/Library/Android/sdk "platform-tools" "platforms;android-36" "build-tools;36.0.0"
npm i -g pnpm@10.33.0

git clone --recursive https://github.com/leakedd/KeepClaw
cd KeepClaw
./scripts/build-native.sh          # brand + patches + frontend + core + console → jniLibs
./scripts/init-keystore.sh         # once: self-signed keystore
./scripts/build-apk.sh assembleRelease
```

Validated with Go 1.27.1, JDK 21, Node 26 / pnpm 10.33, Android SDK platform 36 + build-tools 36.0.0.
No external Java dependency — plain Java + platform APIs (WebView, Service, TileService, Notification).

Since Android 10 an app's `dataDir` is `noexec`, so the Go binaries ship as
`app/src/main/jniLibs/arm64-v8a/libcore.so` and `libconsole.so`: `nativeLibraryDir` is the only executable
area granted to an app, and PackageManager extracts them executable.

## Contributing

**Pull requests are welcome — and merged at my sole discretion.** Opening a PR is not a commitment to merge.
No CLA: by submitting a PR you agree your contribution is licensed under MIT (inbound = outbound).

1. **The engine lives in a pinned submodule.** Never edit `native/picoclaw` directly: engine changes must
   arrive as idempotent patches in `scripts/patches/*.patch` (applied by `scripts/patch-ui.sh`), the way
   branding goes through `scripts/brand.py`.
2. **No secrets in the tree** — keys go to `.security.yml` at runtime.
3. **Every change needs an acceptance criterion that was actually tested** (unit test or emulator run);
   one change per PR, from a clean submodule.
4. Issues and PRs in English.

## Licence and credits

MIT. KeepClaw is a host for [picoclaw](https://github.com/sipeed/picoclaw) by its contributors, pinned at
`bbf6893ca7af` — the upstream copyright notice is reproduced in [LICENSE](LICENSE). The Go import paths
(`github.com/sipeed/picoclaw/...`) are intentionally untouched: they are build identifiers, not branding.
