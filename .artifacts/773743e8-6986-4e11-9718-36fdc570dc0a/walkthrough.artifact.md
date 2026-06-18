# Walkthrough - Strict ARM64-v8a ABI Restriction

I have successfully restricted the app build to strictly target the `arm64-v8a` architecture. This ensures that only 64-bit ARM binaries are included in your APK, significantly reducing size and preventing the inclusion of unused libraries for other architectures.

## Changes Made

### `app` module

#### [build.gradle.kts](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/build.gradle.kts)
- **Removed Unused Variables**: Deleted `enableX86` and `x86Abis` which were previously used for multi-ABI configurations.
- **Strict ABI Filtering**:
    - Explicitly set `ndk.abiFilters` and `cmake.abiFilters` to `["arm64-v8a"]` after clearing any existing values.
- **JNI Excludes**: Added explicit excludes in the `packaging` block to ensure `armeabi-v7a`, `x86`, and `x86_64` libraries are never packaged into the APK, even if provided by external AARs.
- **Splits Optimization**: Simplified the `splits` block as it is no longer needed for a single-ABI target.

## Verification Results

### Automated Tests
- **Gradle Sync**: Successfully completed, confirming the build configuration is valid.
- **Build Configuration**: Verified that `abiFilters` are correctly applied to both C++ and NDK components.

> [!TIP]
> This change will result in a smaller APK that is optimized for modern Android devices. Any dependencies that contain multiple ABIs will now have their non-ARM64 components stripped during the build process.
