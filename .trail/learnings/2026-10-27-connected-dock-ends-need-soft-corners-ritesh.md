---
title: Connected dock ends need soft corners and subtle separation
author: Ritesh
date: 2026-10-27
branch: master
id: l-1e8552f8
scope: repo
status: active
replaces: l-a19d2ac7
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
- The user clarified the connected dock still needs a soft visible separation, not a fully flush square seam. In app/src/main/java/app/gyrolet/mpvrx/ui/browser/NavigationBarState.kt use a 2dp separator and shared 4dp joining-corner token; MainScreen and the mini-player shape continue to use the same dockProgress.
- Keep the synchronized backdrop/tint/blur and edge-aware glass treatment from the earlier correction; do not restore the unsupported concave bridge or independent corner animations.
## Tried and ruled out
- Zero spacing and zero inner radii removed the seam but made the user-facing result too sharp and visually fused. Their latest request explicitly asks for softer connected ends with separation.
## Watch out
- This is a geometry-token correction, not a request to bring back separate pill placement. Keep the saved old-style toggle, smooth interpolation, and matching widths.
