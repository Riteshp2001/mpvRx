# Material 3 Expressive redesign

This branch completes and unifies mpvRx's existing Material 3 Expressive migration. It is based on
the current `master` branch and intentionally preserves playback, MPV configuration, YTDL,
torrents, media scanning, thumbnail caching, navigation state, and Liquid Glass behavior.

## Design-system foundation

- `MpvrxTheme` supplies `MaterialExpressiveTheme`, `MotionScheme.expressive()`, the application
  color schemes, typography, and expanded Material shape scale.
- `AppMotion` centralizes expressive spatial springs, non-overshooting color/alpha effects, and
  reduced-motion handling.
- The bundled Google Sans Flex variable font supplies separate rounded body and wider, graded,
  lightly slanted display cuts. Major display/headline styles use the expressive cut app-wide;
  downloaded fonts, the system-font choice, and non-Latin locale fallback remain respected.
- `Spacing` centralizes layout density.
- `ConnectedShapes` supplies the application-wide connected geometry described below.
- Existing Haze and Kyaant surfaces remain opt-in material treatments layered below the same
  typography, hierarchy, and shape language.

## Connected geometry

`ConnectedShapes.kt` contains a pure, reusable position-to-corners calculation for:

- vertical lists;
- horizontal action groups;
- responsive row-major grids;
- single-item groups;
- incomplete final grid rows; and
- logical start/end corners that mirror automatically in RTL.

The default group uses 28dp exterior corners, 6dp interior corners, and 4dp spacing. Compact action
groups use 20dp exterior corners and 4dp interior corners so small buttons do not collapse into
oversized pills. Shape calculation is remembered by index, visible count, column count, tokens, and
layout direction. Filtering, sorting, or resizing therefore produces the correct new exterior with
no retained positional state.

Connected shapes are only applied when items form one logical control or information group. Media
posters, album artwork, video thumbnails, snapshot galleries, and other independent content cards
remain independent.

## Screen coverage

The branch retains the existing expressive migrations for:

- app theme, dynamic color, typography, motion, and edge-to-edge shell;
- Browser, folders, video library, music library, mini-player, navigation, selection UI, and states;
- Network, SMB/FTP/WebDAV, torrents, Jellyfin, Navidrome, and Audiobookshelf;
- player controls, panels, track sheets, subtitle tools, zoom, quality, equalizer, scopes, and lyrics;
- Settings, Appearance, Liquid Glass, gestures, player, decoder, audio, subtitles, storage, network,
  AI, advanced MPV configuration, codec inspection, About, and Hall of Fame;
- downloads, secure folder, snapshots, image viewer, cast UI, media information, update UI, and
  editor surfaces.

This completion pass adds connected geometry to the main Settings hierarchy, audio and subtitle
track groups, Media Info property grids, Watch Statistics related tiles, and yt-dlp release actions.
The shared helper is available for future intentional groups without coupling it to feature logic.

### Settings organization

- The Settings hub is ordered by user intent: personalization, playback, controls, storage,
  connectivity, AI, advanced/system information, and about.
- Playback presents everyday audio and subtitle choices before the lower-level decoder controls.
- Control layout comes before gesture customization so users establish the visible controls first.
- All ComposePreference-backed detail screens share one expressive theme contract for section
  hierarchy, row density, typography, semantic color, icon emphasis, and spacing.
- Switch rows use animated tonal containers with large (not pill) corners, while custom editors keep
  their purpose-built cards, previews, and drag/drop layouts.
- Appearance's File Browser, Thumbnails, Navigation, and Animation sections use individual
  connected tiles instead of one monolithic card. A selected switch colors the complete row; it no
  longer contracts into an inset card.

### Player layouts and sheets

- The shared player-sheet shell supplies the expressive top geometry, tonal surface, emphasized
  heading, accent-rail section headers, responsive size/insets, reduced-motion behavior, TV focus,
  and optional Liquid Glass treatment to every player sheet.
- Video quality (including HD/YTDL formats), decoder, chapter/bookmark, audiobook, visualizer,
  lyrics-provider, audio-property, audio-track, and subtitle-track choices use full-width connected
  rows. Selection changes the complete tile while keeping trailing actions independently operable.
- Draggable player panels and the control drawer use the expanded expressive panel shape without
  touching video surfaces, gestures, seeking, track selection, or MPV property handling.
- Sheet, panel, drawer, selected-row, switch-color, and expandable-section motion uses shared
  spatial/effect tokens and falls back to short opacity or snap transitions when reduced motion is
  active.

### First-run and permission experience

- The first-run permission flow uses the bundled Google Sans Flex variable font with a dedicated
  wide, lightly slanted display cut for hero headings; non-Latin text keeps the system fallback.
- Permission, configuration, notification, audio, and completion pages use directional page motion,
  fade/scale continuity, and animated progress indicators.
- Motion observes the system animator setting and falls back to short opacity-only transitions when
  reduced motion is requested.
- Existing permission launchers, storage configuration/restore behavior, TV focus, skip rules, and
  onboarding persistence remain unchanged.

### App-wide screen shell and transitions

- Home, Music, Network, Jellyfin, and Profile remain first-class destinations in the same adaptive
  navigation shell; their search and empty/configuration containers use the shared expressive shape
  scale instead of page-local corner values.
- Profile's collapsing identity header uses the centralized expressive spatial spring and degrades
  to an opacity-only transition when system animations are disabled.
- Secure Folder's gate-state transition and expandable codec details use the same reduced-motion
  policy. Timing-sensitive media interactions such as seeking and frame review keep their dedicated
  feedback motion rather than inheriting decorative navigation springs.
- Independent posters, thumbnails, artwork, crop viewports, badges, and image contrast scrims keep
  purpose-specific geometry and colors; they are deliberately not forced into connected groups.

### Hall of Fame

- The creator and maintainer cards form one vertically connected expressive group, while featured
  contributors and top community reporters use RTL-aware horizontal connected geometry.
- Section headers are elevated into distinct tonal containers so contributors, community feedback,
  and the complete contributor list read as separate expressive sections instead of one long grid.
- Expand/collapse feedback uses the shared expressive motion token and respects the system
  reduced-motion setting; avatar caching and repository refresh behavior remain unchanged.

## Performance boundaries

- No player lifecycle, MPV command/property, surface, decoder, YTDL, torrent, or media repository
  code is changed.
- Connected corners are cheap immutable Compose shapes calculated only when group inputs change.
- No blur, shader, graphics layer, or per-frame animation is added to media lists or the player
  video surface.
- Existing lazy-list keys and media-card identity remain unchanged.
- Test-only dependencies do not ship in application APKs.

## Validation

`ConnectedShapesTest` verifies fully rounded single items, every corner of a 2x2 group, incomplete
final rows, and RTL physical mirroring. The normal project checks should run:

```shell
./gradlew ktlintCheck testStandardDebugUnitTest assembleStandardDebug
```

The exact unit-test task can vary with the locally selected distribution/product flavor.

## References

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Material 3 Compose API](https://developer.android.com/reference/kotlin/androidx/compose/material3/)
- [Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)
- [Material expressive motion](https://m3.material.io/blog/m3-expressive-motion-theming)
