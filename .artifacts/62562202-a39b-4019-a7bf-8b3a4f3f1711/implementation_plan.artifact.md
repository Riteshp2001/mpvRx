# Add YouTube Ambient Mode

This plan describes the steps to add a new "YouTube" ambient mode to the player, taking the shader implementation from the `mpvRex` project. When this mode is enabled, all customizable parameters for other ambient modes will be disabled in the UI.

## Proposed Changes

### [MpvRxpx2]

#### [MODIFY] [AmbientShaderBuilder.kt](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/src/main/java/app/gyrolet/mpvrx/ui/player/AmbientShaderBuilder.kt)
- Add `YOUTUBE("YouTube")` to the `AmbientVisualMode` enum.
- Define `AmbientYouTubeShaderSpec` data class.
- Implement `buildYouTube(spec: AmbientYouTubeShaderSpec): String` using the logic from `mpvRex`.
- Update `AmbientShaderBuilder.build` to handle `AmbientYouTubeShaderSpec`.

#### [MODIFY] [PlayerViewModel.kt](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/src/main/java/app/gyrolet/mpvrx/ui/player/PlayerViewModel.kt)
- Update `buildAmbientShader` to handle `AmbientVisualMode.YOUTUBE` and return `AmbientYouTubeShaderSpec`.
- (Optional) Add logic to prevent updating parameters when in YouTube mode if necessary, though the UI will handle the primary disabling.

#### [MODIFY] [AmbientSheet.kt](file:///C:/Users/sagni/StudioProjects/mpvRxpx2/app/src/main/java/app/gyrolet/mpvrx/ui/player/controls/components/sheets/AmbientSheet.kt)
- Add a button for the "YouTube" ambient mode in the "Visual Style" section.
- Update all `SliderItem` components to be disabled when `ambientMode == AmbientVisualMode.YOUTUBE`.
- Disable the "Quality Presets" buttons when in YouTube mode.

## Verification Plan

### Manual Verification
- Deploy the app to a device or emulator.
- Open the Ambient Sheet in the player.
- Select the "YouTube" mode.
- Verify that the ambient glow changes to the YouTube style (sampling random areas).
- Verify that all sliders (Blur Samples, Spread, Glow Intensity, etc.) and Preset buttons are disabled (e.g., greyed out or non-interactive).
- Verify that switching back to "Glow" or "Frame Extend" re-enables the parameters.
