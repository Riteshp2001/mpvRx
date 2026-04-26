<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" alt="MpvRx icon" width="128" />
</p>

# MpvRx
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/Riteshp2001/mpvRx.svg?logo=github&label=GitHub&cacheSeconds=3600)](https://github.com/Riteshp2001/mpvRx/releases/latest)
[![GitHub all releases](https://img.shields.io/github/downloads/Riteshp2001/mpvRx/total?logo=github&cacheSeconds=3600)](https://github.com/Riteshp2001/mpvRx/releases/latest)

`MpvRx` is a fork in the `mpvExtended` lineage, carried forward by one of the original contributors behind that project. It keeps the `mpv-android` and `libmpv` backbone, then pushes harder on the details that shape everyday playback: browsing, subtitles, playlists, resume behavior, and player polish.

No ads. No trackers. No noise. Just a serious video player with a calmer surface and a sharper edge.

## Latest Updates
 - See (CHANGELOG)[CHANGELOG.md]


## Why This Fork Exists
`mpvExtended` already proved how far an Android `mpv` player could go. `MpvRx` keeps that spirit, but leans into feel: fewer dead taps, better timing, stronger playback stability, and less friction between you and the file you actually wanted to watch.

## Download
### Stable Release
[![Download Release](https://img.shields.io/badge/Download-Release-blue?style=for-the-badge)](https://github.com/Riteshp2001/mpvRx/releases)

### Preview Builds
[![Download Preview Builds](https://img.shields.io/badge/Download-Preview%20Builds-red?style=for-the-badge)](https://riteshp2001.github.io/mpvRx/)

If something breaks, feels off, or deserves another pass, report it in the [Issues](https://github.com/Riteshp2001/mpvRx/issues).

## Build
### Requirements
- JDK 17
- Android SDK with modern build tools installed
- Git

### Debug Build
```powershell
./gradlew.bat :app:assembleStandardDebug
```

### Release Variants
- `standard`: the main release with in-app update support
- `playstore`: Play Store-friendly flavor with store-safe defaults
- `fdroid`: updater-free flavor for F-Droid style distribution

### APK Variants
- `universal`: works on all supported devices
- `arm64-v8a`: recommended for most current Android devices
- `armeabi-v7a`: for older 32-bit ARM devices
- `x86`: for 32-bit Intel and AMD Android devices
- `x86_64`: for 64-bit Intel and AMD Android devices

## Release Notes For Maintainers
To cut a signed GitHub release through Actions, configure these repository secrets:

| Secret Name              | Description                                          |
|--------------------------|------------------------------------------------------|
| `SIGNING_KEYSTORE`       | Base64-encoded keystore file (`.jks` or `.keystore`) |
| `SIGNING_KEY_ALIAS`      | Key alias inside the keystore                        |
| `SIGNING_STORE_PASSWORD` | Password for the keystore                            |
| `KEY_PASSWORD`           | Password for the signing key                         |

Then bump `versionCode` and `versionName` in `app/build.gradle.kts`, create a tag, and push it:

```bash
git tag -a v1.3.1 -m "Release version 1.3.1"
git push origin v1.3.1
```

Preview releases use the same flow with preview tags such as:

```bash
git tag -a v1.3.1-preview.1 -m "Preview release"
git push origin v1.3.1-preview.1
```

## Acknowledgments
- [mpv-android](https://github.com/mpv-android)
- [mpvExtended](https://github.com/marlboro-advance/mpvEx)
- [mpvKt](https://github.com/abdallahmehiz/mpvKt)
- [Next Player](https://github.com/anilbeesetti/nextplayer)
- [Gramophone](https://github.com/FoedusProgramme/Gramophone)

## Star History
<a href="https://www.star-history.com/#Riteshp2001/mpvRx&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=Riteshp2001/mpvRx&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=Riteshp2001/mpvRx&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=Riteshp2001/mpvRx&type=date&legend=top-left" />
 </picture>
</a>
