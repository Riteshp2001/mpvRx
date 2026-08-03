# MPV Overridden Properties (Runtime `setProperty` Calls)

Complete inventory of every mpv property overridden at runtime via `MPVLib.setProperty*` across the mpvRx codebase, organized by mpv property name. Each entry lists the source file(s) and line(s) where the override happens.

> Note: this documents runtime **property** overrides. Init-time **option** overrides (`setOptionString`, e.g. in `MPVView.initOptions()`) are not listed here.

---

## Playback Core

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `pause` | bool | `true` / `false` | `MediaPlaybackService.kt:314,319` · `MPVPipHelper.kt:68,69` · `MpvTeardownCoordinator.kt:110` · `PlayerActivity.kt:3170,4123` · `PlayerViewModel.kt:1145,1940,3384,3388,3397,3406` |
| `time-pos` | double | seconds | `MediaPlaybackService.kt:341` · `PlayerActivity.kt:2389` · `PlayerViewModel.kt:2502,2514` |
| `speed` | float | playback speed | `GestureHandler.kt:530,681,840` · `PlayerControls.kt:353,360,1797,1804` · `PlayerActivity.kt:3764` |
| `chapter` | int | chapter index | `PlayerControls.kt:338,1782` |
| `keep-open` | bool | `true` | `MPVView.kt:190` |
| `input-default-bindings` | bool | `true` | `MPVView.kt:191` |
| `hr-seek` | string | `yes` / `no` | `PlayerViewModel.kt:1314` |
| `hr-seek-framedrop` | string | `yes` / `no` | `PlayerViewModel.kt:1315` |
| `ab-loop-a` / `ab-loop-b` | double/string | position or `no` | `PlayerViewModel.kt:4848,4853,4859,4864,4869,4870` |
| `vo` | string | `null` (teardown) | `MpvTeardownCoordinator.kt:112` |

## Audio

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `audio-channels` | string | `auto-safe` / `auto` / `mono` / `stereo` | `AudioTracksSheet.kt:110,112` · `PlayerActivity.kt:993,995` (from `AudioChannels` enum, `AudioPreferences.kt:60-72`) |
| `af` | string | `pan=[stereo\|c0=c1\|c1=c0]` (reverse stereo) | `PlayerViewModel.kt:689` · `AudioChannels.ReverseStereo` (`AudioPreferences.kt:71`) |
| `audio-delay` | double | seconds | `AudioDelayPanel.kt:61,64` · `PlayerActivity.kt:3767` |
| `audio-pitch-correction` | bool | preference value | `PlayerActivity.kt:3766` · `PlaybackSpeedSheet.kt:217` |
| `volume` | int | 0–100 | `PlayerViewModel.kt:1286,3920` |
| `volume-max` | string | max volume | `PlayerViewModel.kt:1281` |

## Video & Decoding

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `hwdec` | string | hwdec mode | `PlayerControls.kt:343,1787` |
| `vd-lavc-dr` | string | `yes` / `no` | `PlayerControls.kt:345,348,1789,1792` |
| `framedrop` | string | `vo` / `no` | `PlayerControls.kt:346,349,1790,1793` |
| `brightness` | int | −100…100 | `VideoSettingsFiltersCard.kt:68,83` · `VideoSettingsFilterPresetsCard.kt:110` · `PlayerActivity.kt:3570` · `PlayerEnums.kt:311-350` (`VideoFilters.BRIGHTNESS`) |
| `saturation` | int | −100…100 | `VideoSettingsFiltersCard.kt:68,83` · `VideoSettingsFilterPresetsCard.kt:111` · `PlayerActivity.kt:3570` · `PlayerEnums.kt:323-327` |
| `contrast` | int | −100…100 | `VideoSettingsFiltersCard.kt:68,83` · `VideoSettingsFilterPresetsCard.kt:112` · `PlayerActivity.kt:3570` · `PlayerEnums.kt:328-332` |
| `gamma` | int | −100…100 | `VideoSettingsFiltersCard.kt:68,83` · `VideoSettingsFilterPresetsCard.kt:113` · `PlayerActivity.kt:3570` · `PlayerEnums.kt:333-337` |
| `hue` | int | −100…100 | `VideoSettingsFiltersCard.kt:68,83` · `VideoSettingsFilterPresetsCard.kt:114` · `PlayerActivity.kt:3570` · `PlayerEnums.kt:338-342` |
| `sharpen` | int | −5…5 | `VideoSettingsFiltersCard.kt:68,83` · `VideoSettingsFilterPresetsCard.kt:115` · `PlayerActivity.kt:3570` · `PlayerEnums.kt:343-349` |
| `deband-iterations` | int | 0–16 | `VideoSettingsDebandCard.kt:116,138` · `PlayerEnums.kt:359-365` |
| `deband-threshold` | int | 0–200 | `VideoSettingsDebandCard.kt:116,138` · `PlayerEnums.kt:366-372` |
| `deband-range` | int | 1–64 | `VideoSettingsDebandCard.kt:116,138` · `PlayerEnums.kt:373-379` |
| `deband-grain` | int | 0–64 | `VideoSettingsDebandCard.kt:116,138` · `PlayerEnums.kt:380-386` |
| `video-zoom` | double | zoom factor | `PlayerActivity.kt:3248,3771` · `PlayerViewModel.kt:2459,4207` |
| `video-pan-x` / `video-pan-y` | double | 0.0 reset | `PlayerViewModel.kt:2467,2468` |
| `video-scale-x` / `video-scale-y` | double | 1.0 reset | `PlayerViewModel.kt:5090,5091,5411,5412` |
| `video-align-x` / `video-align-y` | double | preserved | `Anime4KPlayback.kt:173-188` (geometry restore) |
| `video-aspect-override` | string/double | `no` / ratio | `PlayerViewModel.kt:3970,3974,4000,4022` |
| `video-unscaled` | string | preserved | `Anime4KPlayback.kt:190-193` (geometry restore) |
| `panscan` | double | 0.0 / 1.0 | `PlayerViewModel.kt:3969,3975,4001,4021` |
| `glsl-shaders` | string | `:`-joined shader list | `HdrToysManager.kt:89` · `Anime4KPlayback.kt:204` |

## Subtitle Styling

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `sub-bold` | bool | toggle | `SubtitleSettingsTypographyCard.kt:158,315` |
| `sub-italic` | bool | toggle | `SubtitleSettingsTypographyCard.kt:172,316` |
| `sub-ass-justify` | bool | toggle | `SubtitleSettingsTypographyCard.kt:186,325` |
| `sub-justify` | string | justify mode / `auto` | `SubtitleSettingsTypographyCard.kt:189,193,317` |
| `sub-font` | string | font name | `SubtitleSettingsTypographyCard.kt:231,318` |
| `sub-font-size` | int | size | `SubtitleSettingsTypographyCard.kt:245,319` |
| `sub-border-style` | string | style | `SubtitleSettingsTypographyCard.kt:258,323` |
| `sub-border-size` | int | size | `SubtitleSettingsTypographyCard.kt:275,320` |
| `sub-outline-size` | int | size | `SubtitleSettingsTypographyCard.kt:276,321` |
| `sub-shadow-offset` | int | offset | `SubtitleSettingsTypographyCard.kt:293,322` |
| `secondary-sub-bold` | bool | toggle | `SubtitleSettingsTypographyCard.kt:159,315` |
| `secondary-sub-italic` | bool | toggle | `SubtitleSettingsTypographyCard.kt:173,316` |
| `secondary-sub-justify` | string | justify mode | `SubtitleSettingsTypographyCard.kt:190,194,317` |
| `secondary-sub-font` | string | font name | `SubtitleSettingsTypographyCard.kt:232,318` |
| `secondary-sub-font-size` | int | size | `SubtitleSettingsTypographyCard.kt:246,319` |
| `secondary-sub-border-style` | string | style | `SubtitleSettingsTypographyCard.kt:259,323` |
| `secondary-sub-border-size` | int | size | `SubtitleSettingsTypographyCard.kt:277,320` |
| `secondary-sub-outline-size` | int | size | `SubtitleSettingsTypographyCard.kt:278,321` |
| `secondary-sub-shadow-offset` | int | offset | `SubtitleSettingsTypographyCard.kt:294,322` |
| `sub-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:140,202` (from `SubColorType.Text`, `SubtitleSettingsColorsCard.kt:164-189`) |
| `sub-border-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:140,202` (`SubColorType.Border`) |
| `sub-back-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:140,202` (`SubColorType.Background`) |
| `sub-shadow-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:140,202` (`SubColorType.Shadow`) |
| `secondary-sub-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:142,203` |
| `secondary-sub-border-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:142,203` |
| `secondary-sub-back-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:142,203` |
| `secondary-sub-shadow-color` | string | `#AARRGGBB` | `SubtitleSettingsColorsCard.kt:142,203` |
| `sub-scale` | float | scale factor | `SubtitleSettingsMiscellaneousCard.kt:120,157` · `GestureHandler.kt:955` · `PlayerActivity.kt:3551` |
| `sub-scale-by-window` | string | `yes` / `no` | `SubtitleSettingsMiscellaneousCard.kt:92,165` · `PlayerActivity.kt:3549` |
| `sub-use-margins` | string | `yes` / `no` | `SubtitleSettingsMiscellaneousCard.kt:93,166` · `PlayerActivity.kt:3550` |
| `blend-subtitles` | string | blend mode | `SubtitleSettingsMiscellaneousCard.kt:107,170` · `PlayerActivity.kt:3533` · `PlayerViewModel.kt:5092,5415` |
| `sub-ass-override` | string | override value | `SubtitlePositioning.kt:131` |
| `secondary-sub-ass-override` | string | override value | `SubtitlePositioning.kt:132` |
| `sub-pos` | int | position % | `SubtitlePositioning.kt:141` · `PlayerActivity.kt:3760` |
| `secondary-sub-pos` | int | position % | `SubtitlePositioning.kt:159` |
| `sub-delay` | double | seconds | `SubtitleDelayPanel.kt:72,82` · `PlayerActivity.kt:3763` · `PlayerViewModel.kt:3313` |
| `sub-speed` | double | factor | `SubtitleDelayPanel.kt:75,83` · `PlayerActivity.kt:3768,3789` |

## Track Selection

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `vid` | string/int | `auto` / `no` / id | `PlayerActivity.kt:3165,4121,5841,5862` |
| `sid` | string/int | id or `no` | `MPVView.kt:131` (`TrackDelegate`) · `PlayerViewModel.kt:2027,3333,3335` · `PlayerActivity.kt:2457` · `TrackSelectionUtils.kt:23` |
| `secondary-sid` | string/int | id or `no` | `MPVView.kt:132` (`TrackDelegate`) · `PlayerViewModel.kt:3332,3334` · `TrackSelectionUtils.kt:23` |
| `aid` | string/int | id or `no` | `MPVView.kt:133` (`TrackDelegate`) · `TrackSelectionUtils.kt:23` |
| `track-list/<index>/title` | string | display name | `SubtitleOps.kt:168` · `PlayerActivity.kt:2457` |

## Network & Streaming

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `user-agent` | string | UA string | `PlayerActivity.kt:2711` |
| `http-header-fields` | string | header lines | `PlayerActivity.kt:2717` |

## HDR / Color Pipeline (runtime applied)

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `target-colorspace-hint` | string | `auto` / `no` / `yes` | `HdrScreenOutput.kt:96,113,129` (applied at `HdrScreenOutput.kt:149-162`) |
| `target-colorspace-hint-mode` | string | `target` | `HdrScreenOutput.kt:97,114` |
| `target-prim` | string | profile value | `HdrScreenOutput.kt:98,115` |
| `target-trc` | string | profile value | `HdrScreenOutput.kt:99,116` |
| `target-peak` | string | `auto` | `HdrScreenOutput.kt:100,117` |
| `inverse-tone-mapping` | string | `auto` / `no` / `yes` | `HdrScreenOutput.kt:101,118,131` |
| `tone-mapping` | string | `auto` / `clip` | `HdrScreenOutput.kt:102,119,132` |
| `gamut-mapping-mode` | string | `auto` / `clip` | `HdrScreenOutput.kt:103,120,133` |
| `hdr-compute-peak` | string | `auto` / `no` / `yes` | `HdrScreenOutput.kt:104,121,134` |
| `hdr-reference-white` | string | `203` | `HdrScreenOutput.kt:105,122,135` |
| `tone-mapping-visualize` | string | `no` | `HdrScreenOutput.kt:106,123,130` |
| `glsl-shader-opts` | string | profile / empty | `HdrScreenOutput.kt:107,124,136` |

## Media Title

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `force-media-title` | string | preferred title | `PlayerActivity.kt:3305,3409,5156` |

## Internal `user-data` Properties (script ↔ native bridge)

| mpv Property | Type | Values | Location |
|---|---|---|---|
| `user-data/mpvrx/curl_response` | string | JSON response | `ScriptCurlBridge.kt:369-372` (`RESPONSE_PROPERTY`, defined at `ScriptCurlBridge.kt:42`) |
| `user-data/mpvrx/custombuttons_lua_loaded` | string | `0` / `1` | `PlayerViewModel.kt:1683,1696` (property defined at `:1103`) |
| `user-data/mpvrx/custombuttons_lua_version` | string | version | `PlayerViewModel.kt:1697` (defined at `:1104`) |
| `user-data/mpvrx/custombuttons_js_loaded` | string | `0` / `1` | `PlayerViewModel.kt:1683,1696` (defined at `:1109`) |
| `user-data/mpvrx/custombuttons_js_version` | string | version | `PlayerViewModel.kt:1697` (defined at `:1110`) |
| `user-data/mpvrx/custombuttons_loaded` (legacy) | string | `0` / `1` | `PlayerViewModel.kt:1703` (defined at `:1096`) |
| `user-data/mpvrx/custombuttons_version` (legacy) | string | version | `PlayerViewModel.kt:1704` (defined at `:1097`) |
| `user-data/android/battery-level` | int | battery % | `PlayerViewModel.kt:1404` |
| `user-data/android/battery-charging` | bool | charging state | `PlayerViewModel.kt:1405` |
| `user-data/android/battery-plugged` | bool | plugged state | `PlayerViewModel.kt:1406` |
| `user-data/...` (dynamic) | string | cleared to `""` | `PlayerViewModel.kt:4096` (command bridge reset) |

---

## Dynamic / Delegated Property Setters

These set properties via indirection (variable names resolved at runtime):

| Setter | Properties Covered | Location |
|---|---|---|
| `TrackDelegate` (`sid`, `secondary-sid`, `aid`) | track selection ids | `MPVView.kt:106-133` |
| `setTrackSelectionId(property, id)` | `sid`, `secondary-sid` | `TrackSelectionUtils.kt:18-24` |
| `AudioChannels` enum (`property` + `value`) | `audio-channels`, `af` | `AudioPreferences.kt:60-72` |
| `VideoFilters` enum (`mpvProperty`) | `brightness`, `saturation`, `contrast`, `gamma`, `hue`, `sharpen` | `PlayerEnums.kt:311-350` |
| `DebandSettings` enum (`mpvProperty`) | `deband-iterations`, `deband-threshold`, `deband-range`, `deband-grain` | `PlayerEnums.kt:352-388` |
| `SubColorType` enum (`property`) | `sub-color`, `sub-border-color`, `sub-back-color`, `sub-shadow-color` (+ `secondary-sub-*` variants) | `SubtitleSettingsColorsCard.kt:164-189` |
| `applyHdrScreenOutputProperties()` | full HDR property set | `HdrScreenOutput.kt:155-163` |
| Anime4K geometry snapshot/restore | `video-zoom`, `video-pan-x`, `video-pan-y`, `video-align-x`, `video-align-y`, `video-aspect-override`, `panscan`, `brightness`, `contrast`, `saturation`, `gamma`, `hue`, `sharpen`, `video-unscaled` | `Anime4KPlayback.kt:150-193` |
| Typography apply function (`${prefix}` = `sub-` / `secondary-sub-`) | font, font-size, bold, italic, justify, border-style, border-size, outline-size, shadow-offset, scale, scale-by-window, use-margins, colors | `PlayerActivity.kt:3533-3551` |

---

## Also Observed (read-back, not overrides)

For completeness — properties registered with `MPVLib.observeProperty` (not overridden, but monitored):

| mpv Property | Location |
|---|---|
| `pause`, `media-title`, `metadata/artist`, `time-pos`, `duration`, `chapter` | `MediaPlaybackService.kt:186-191` |
| `video-params/aspect`, `video-params/w`, `video-params/h`, `container-fps` | `PlayerObserver.kt:19-22` · `MPVView.kt:331` |
| Various player state properties | `MPVView.kt:235` (`observedProps`) |
