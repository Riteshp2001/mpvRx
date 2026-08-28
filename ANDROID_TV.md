# Android TV support

Android TV support is developed on the `experimental/android-tv` branch. The
same APK remains usable on phones and tablets; TV behavior is enabled only when
Android reports television UI mode, Leanback support, or television hardware.

## Navigation

- D-pad navigation uses normal Compose focus traversal throughout browser,
  network, Jellyfin, playlist, settings, picker, and Mini Player surfaces.
- Focused custom cards show a three-pixel primary-color ring and a small scale
  increase. Lazy lists and navigation destinations restore their focused child.
- TV destinations use a 48 dp horizontal and 24 dp vertical safe area.
- Touch-style tab paging is disabled on TV. Tabs change when their navigation
  item receives focus; phone and tablet swiping is unchanged.
- Back dismisses the current player sheet or panel, then hides player controls,
  then follows the existing playback exit behavior.

## Playback remote controls

| Key | Hidden player controls | Visible controls or dialog |
| --- | --- | --- |
| D-pad Left / Right | Seek backward / forward | Move focus |
| D-pad Up / Down | Show controls | Move focus |
| Center / Enter / gamepad A | Play or pause | Activate focused control |
| Menu / Guide | Open More controls | Open More controls unless a dialog owns focus |
| Rewind / Fast-forward / L2 / R2 | Seek backward / forward | Seek backward / forward |
| Previous / Next / L1 / R1 | Configured previous / next action | Configured previous / next action |
| Play / Pause / Stop | Direct transport action | Direct transport action |
| Captions / Audio track | Open the corresponding track picker | Dialog keeps focus |
| 0-9 | Jump to 0%-90% of the timeline | Jump to 0%-90% of the timeline |
| Channel Up / Down | Playback speed +/-0.05x | Playback speed +/-0.05x |

Holding Left or Right repeats seeking at a throttled cadence. Player controls do
not auto-hide while navigating with a remote. Touch-only screen locking and
rotation controls are omitted on TV, and playback is kept in sensor landscape.

## Packaging

The manifest includes both `LAUNCHER` and `LEANBACK_LAUNCHER`, a 320x180 xhdpi
banner, and optional touchscreen, microphone, Leanback, and USB-host features.
This keeps one universal APK installable on mobile, Android TV, Google TV, and
Fire TV-class devices without requiring TV libraries in mobile composition.

## Verification

Pure policy tests cover form-factor detection and remote mapping. Recommended
device validation uses a 1080p Android TV emulator and one physical remote-only
device:

1. Cold-launch and traverse every main tab, list/grid, settings section, dialog,
   and Back path without touch or a mouse.
2. Play local, SMB/WebDAV/FTP, HTTP, Jellyfin, playlist, audio, and subtitle
   content; return from Player and verify focus restoration.
3. Exercise all remote mappings above with controls hidden and visible.
4. Verify Mini Player re-entry, background playback, PiP (when supported), Cast,
   screen sleep/wake, and process recreation.
5. Repeat a smoke pass on a phone to verify gestures, pager swipes, orientation,
   predictive Back, and player controls remain unchanged.

Design and input behavior follow Android's official `android/tv-samples`
JetStream Compose patterns, with remote semantics informed by mpv-android and
NOVA Video Player.