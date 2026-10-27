---
title: App-wide Sonner toasts need one lifecycle host and a native fallback
author: Ritesh
date: 2026-10-27
branch: master
id: l-5ad97356
scope: repo
status: active
src: agent
touches:
  - app/build.gradle.kts
  - app/src/main/java/app/gyrolet/mpvrx/App.kt
  - app/src/main/java/app/gyrolet/mpvrx/domain/download/AppDownloadManager.kt
  - app/src/main/java/app/gyrolet/mpvrx/presentation/crash/CrashActivity.kt
  - app/src/main/java/app/gyrolet/mpvrx/presentation/crash/DebugLogsScreen.kt
  - app/src/main/java/app/gyrolet/mpvrx/ui/browser/MainScreen.kt
  - app/src/main/java/app/gyrolet/mpvrx/ui/browser/NavigationBarState.kt
  - app/src/main/java/app/gyrolet/mpvrx/ui/browser/components/BrowserTopBar.kt
  - app/src/main/java/app/gyrolet/mpvrx/ui/browser/components/PlaybackQueueActions.kt
  - app/src/main/java/app/gyrolet/mpvrx/ui/browser/components/VideoSwipeActions.kt
---

## Decided
- App-owned toast calls in app/src route through ui/components/AppToast.kt. Its main-thread adapter preserves makeText/show/setText/cancel semantics, uses explicit type metadata rather than guessing from translated text, and retains native fallback when no eligible Compose host exists.
- ui/components/AppToastHost.kt is installed once by MpvrxTheme with a CompositionLocal guard for nested themes. Use Sonner v0.4.0 for popup stacking, transitions, and swiping, and render compact Haze content inside its messageSlot so blur follows the animated toast rather than a static outer wrapper.
- Keep typed success/info/warning/error/loading variants, same-ID loading updates, AppIcon and emoji overrides, action support, a close button, accessibility-adjusted durations, bounded visible messages, and lifecycle cleanup. Background/PiP/startup messages still use the native fallback because there is no suitable interactive UI surface.
## Tried and ruled out
- Per-screen toaster instances duplicate messages under nested themes and cannot receive ViewModel/service notifications reliably. Guessing severity from English strings breaks localization. Looking at Sonner internal toast state is not a public API; keep adapter metadata outside the library.
## Watch out
- Preserve the pinned ToasterState API and keep theme color reads in composition. Loading work must update or cancel its handle. Do not lose critical startup/crash notifications while the Compose host is unavailable.
