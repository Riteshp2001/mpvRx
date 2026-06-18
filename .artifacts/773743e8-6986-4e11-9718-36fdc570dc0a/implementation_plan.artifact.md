# Implementation Plan - Performance, Thermal & Architecture Optimization (Revised)

This plan describes the steps to improve the app's responsiveness, reduce battery drain, lower heating, and strictly restrict the APK to only support the `arm64-v8a` architecture, removing all other ABIs.

## User Review Required

> [!IMPORTANT]
> **Strict Architecture Restriction**: The APK will be strictly restricted to **ARM64 (64-bit)** only. We will remove all unused ABI configurations and explicitly filter out any other architectures (32-bit ARM, x86, x86_64) to ensure they are not included in the final package.

> [!NOTE]
> **Adaptive Quality**: The app will automatically lower some visual settings (like shader complexity) if it detects the device is getting hot. This prevents "hard throttling" by the OS, which causes severe stuttering.

## Proposed Changes

### `app` module

#### [MODIFY] [build.gradle.kts](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/build.gradle.kts)
- **ABI Cleanup**: Remove unused `enableX86` and `x86Abis` variables.
- **Strict ABI Filtering**:
    - In `defaultConfig.ndk`, use `abiFilters.set(listOf("arm64-v8a"))` (or clear and add) to ensure ONLY ARM64 is targeted.
    - Add explicit `jniLibs` excludes in the `packaging` block for `armeabi-v7a`, `x86`, and `x86_64` to prevent any accidental inclusion from AAR dependencies.
    - Clean up the `splits` block to avoid confusion.

#### [MODIFY] [ThermalMonitor.kt](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/src/main/java/app/gyrolet/mpvrx/ui/player/ThermalMonitor.kt)
- Add a new helper `shouldForceFastScaling(headroom: Float): Boolean` for critical thermal states (< 0.35).

#### [MODIFY] [PlayerViewModel.kt](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt)
- **Remove Redundant Polling**: Remove the integer-based `time-pos` collector. The adaptive polling loop already provides higher precision with less overhead.
- **Adaptive Ambient Quality**: Re-evaluate ambient sample budgets when thermal headroom changes significantly.
- **Stats Optimization**: Increase loop delay when paused or overheating.

#### [MODIFY] [MPVView.kt](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/src/main/java/app/gyrolet/mpvrx/ui/player/MPVView.kt)
- **Adaptive Scaling**: Inject `scale=bilinear` and `cscale=bilinear` (faster/cooler) when in a critical thermal state.
- **Cache Tuning**: Set `demuxer-max-bytes=150M` and `demuxer-max-back-bytes=50M` for better seek performance.
- **Fast Startup**: Defer asset copying and yt-dlp setup until needed.

#### [MODIFY] [AmbientShaderBuilder.kt](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/src/main/java/app/gyrolet/mpvrx/ui/player/AmbientShaderBuilder.kt)
- **Precision Optimization**: Switch ambient shaders to `precision mediump float;` (16-bit) for faster GPU performance and lower heat.

## Verification Plan

### Manual Verification
- **Architecture**: Build the APK and verify its size decreases and it only contains `lib/arm64-v8a`.
- **Thermal Performance**: Monitor temperature via "Stats Page 6" and verify adaptive quality kicks in when hot.
- **Seek Responsiveness**: Verify snappy seeking due to optimized demuxer back-buffer.
- **Battery**: Monitor discharge rate during long playback sessions.
