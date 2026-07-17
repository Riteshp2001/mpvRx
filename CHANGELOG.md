# Changelog

These notes are written in plain English and focus on what changed for real use.

## 1.5.0-preview.4 — Preview Release

### Google Cast
- **Native Cast control**: Added the standard stateful Google Cast button to portrait and landscape player controls, including a one-time layout migration for existing users.
- **Local and remote media handoff**: Receiver-accessible HTTP(S) streams are loaded directly; Android-local `file://` and `content://` media use a tokenized temporary LAN server with byte-range seeking and CORS support.
- **Playback continuity**: The sender transfers title, play state, duration, and current position, pauses local playback after a successful receiver load, and restores local playback at the receiver position when casting ends.
- **Complete sender controls**: Added Cast SDK expanded controls, notification/lock-screen integration, reconnection support, and receiver volume controls.
- **Receiver compatibility guardrail**: Casting uses Google's Default Media Receiver, so containers and codecs unsupported by the selected TV/Chromecast are not transcoded automatically.

### Playback Lifecycle & Background Playback
- **One background playback switch**: Audio preferences and the player background button now control the same persistent setting for both audio and video.
- **Reliable screen lock and unlock handling**: Playback now follows the selected background policy across screen-off, lock-screen, unlock, and resume transitions without losing the prior play state.
- **PiP lifecycle coordination**: Picture-in-picture transitions, PiP dismissal, screen locking while in PiP, and activity teardown now share one lifecycle policy instead of competing playback paths.
- **Persistent task-removal playback**: Swiping the app out of Recents no longer kills an active foreground playback session, so background audio continues until explicitly stopped.
- **Foreground service cleanup**: Disabling background playback immediately pauses playback and stops its foreground service and notification.
- **Preference migration**: Existing users who enabled the legacy audio-only screen-lock option are migrated to the unified background playback setting.

### Subtitle Search
- **Results appear as they arrive**: Online subtitle results are merged into the list as each selected SubHub source or Wyzie completes instead of waiting for every request.
- **Race-free repeat searches**: Starting another subtitle search cancels the previous request and clears stale results before streaming the new matches.

### Playback Engine
- **Updated bundled mpvlib**: Refreshed the packaged `mpvlib.aar` used by all preview APK variants.

### Media Library & Audio Browsing
- **Preference-aware media switch**: The Video/Audio selector appears only when audio browsing is enabled, remembers the selected library type, and resets to Video when audio browsing is disabled.
- **Complete audio playlists**: Audio launches now populate the active playlist so previous and next controls update and navigate correctly.
- **Square audio artwork**: Audio thumbnails use a square presentation in both grid and list cards while video thumbnails remain 16:9.
- **Audio-safe editing**: Video compression actions are hidden for audio selections and guarded from opening with audio files.

### Player Polish
- **Rounded streaming cache indicator**: Buffered seekbar progress is clipped to the same pill-shaped ends as the normal seek track.
- **Smoother natural visualizer**: Higher-rate spectrum capture, tuned frequency envelopes, frame-time-aware interpolation, and responsive beat decay make the audio blob react fluidly without harsh jumps.

### AI Providers & Smart Tools
- **Current provider protocols**: OpenAI, Anthropic, Groq, OpenRouter, Together, and OpenCode Zen requests now follow their current API shapes; OpenCode models are routed through Responses, Messages, Gemini, or Chat Completions as required.
- **Resilient response parsing**: Text, multipart content, reasoning blocks, citations, provider errors, and both object- and array-based model lists are parsed without relying on one rigid response schema.
- **Thinking-model rename fixes**: AI rename and subtitle-title output now discard reasoning and code fences, accept structured JSON fields, preserve extensions, and reject empty or unsafe names.
- **Provider-specific model memory**: Each provider keeps its own selected model and cached model list, preventing stale selections when switching services.
- **Correct speech endpoints**: Groq and OpenAI use supported transcription models, while OpenRouter speech requests use its current base64 JSON audio format.

## 1.5.0-preview.3 — Preview Release

### Playback Hotfix
- **Audio and video transitions rebuilt**: Every item is loaded with file-local video-track selection, preventing audio state from producing a black screen on the next video.
- **Reliable next-item playback**: Runtime navigation now sends an actual mpv `loadfile` replacement and clears the previous EOF pause state.
- **Preference-safe EOF handling**: Autoplay, Repeat One, Repeat All, Shuffle, playlist mode, and close-at-end now behave consistently, including very short audio files.
- **Safe seeking**: Audio never enters the native video-thumbnail path, and video thumbnails are generated only on demand while scrubbing—not automatically during file load.
- **Separated render surfaces**: The blob surface is mounted only for media independently identified as audio, preventing it from covering video during track-list transitions.
- **True beat response**: With audio-capture permission, real FFT data exclusively drives the blob; brightness and bloom are reduced while bass onsets pulse more clearly.

### 🔊 Audio Blob Visualizer
- **OpenGL ES 3.0 blob visualizer** — when playing audio without cover art, a reactive 3D blob appears behind the player controls. The blob morphs, pulses, and shifts color based on audio energy with a bloom/glow post-process.
- **Touch rotation**: Drag the blob to rotate it in 3D.
- **Pinch zoom fixed**: The blob now properly shrinks/enlarges when pinching — `pinchScaleFromRenderer()` was hardcoded to always return `1f`, resetting zoom on every new gesture.
- **Smaller default size**: Camera distance increased so the blob fits cleanly within screen bounds instead of nearly filling the height.
- **Audio Preferences toggle**: New "Audio blob visualizer" switch in Settings > Audio to enable or disable it.
- **Audio filter setting moved**: Audio filter (compressor/equalizer) options now live in Audio Preferences instead of the player's MoreSheet.

### 🎬 yt-dlp Changes
- **Audio quality preferences**: Independent bitrate caps for `Auto`, 64, 128, 192, and 256 kbps — composed with existing codec, resolution, FPS, HDR, and container selectors.
- **Serialized URL loading**: Initial and replacement URL loads now use one cancellable serialized job, preventing overlapping libmpv commands when links are pasted rapidly.
- **Graceful error recovery**: Recoverable URL load failures return to the player UI with an error message instead of escaping to the process-wide crash handler.
- **Audio quality removed from MoreSheet**: yt-dlp audio quality selector moved from the player's MoreSheet to yt-dlp settings.

### 📻 Audio Browsing
- **MediaStore + filesystem discovery** for common audio formats, neutral media counts, audio MIME mapping, and Android 13 `READ_MEDIA_AUDIO` permission handling.
- **Audio cards** show metadata titles, embedded cover artwork when available (via `audio-display=embedded-first`), and a music-note fallback icon.
- **Portrait-only playback** — audio files force sensor-portrait orientation and prevent the rotation action from switching back to landscape.
- **Sibling playlist includes audio** — when "Include audio" is on, the next/previous track list includes audio files from the same folder.
- **Audio icon placeholder fix**: Exported icon now displays correctly for audio files without cover art.
- **Audio pitch correction fix**: Pitch correction no longer persists when switching to a new video — resets to default per-file.
- **Audio autoplay race condition fixed**: Eliminated a race condition that could cause audio files to fail starting playback.

### 🌐 Network & External Playback
- **WebDAV PROPFIND fix**: Connection checks now use a depth-zero `PROPFIND` request instead of Sardine's `HEAD`-based `exists()` call, making it work with servers like FileBrowser Quantum that reject `HEAD` on DAV collections.
- **WebDAV trailing slash**: Collection URLs consistently keep a trailing slash during validation and browsing.
- **External-player discovery**: Added a MIME-only intent filter so external-player pickers can find mpvRx before attaching the final video or audio URI.
- **More protocol support**: Added `gopher://`, `sctp://`, and `data://` to network stream detection and intent filters.
- **Stream compatibility improved**: Better handling of edge-case media streams and improved browsing reliability.

### 🌲 Tree View Navigation
- **Configurable path compression**: New `Off`, 1–5, and `Unlimited` choices for single-child folder flattening. Applied independently per navigation step, preserving predictable physical paths. Tree View refreshes instantly when the depth changes.

### 🎨 Icon Consistency
- Converted all three `painterResource(R.drawable.ic_material_symbols_check)` usages to `Icons.Default.Check` through the app's `AppIcon` / `Icon` system.
- `SectionHeader.leadingIcon` and `CompactExpressiveIconButton.imageVector` now accept `AppIcon` instead of raw `ImageVector`, keeping everything on the unified icon pipeline.

### 🔊 Audio Playback Runtime Fixes
- **`local_media_path` extra**: Internal launches now pass the resolved filesystem path alongside the content URI, giving mpv a reliable fallback when `content://` URIs fail.
- **Serialized load dispatcher**: Added a dedicated `Dispatchers.Default.limitedParallelism(1)` dispatcher for media loading — prevents race conditions when queuing multiple load commands.
- **`vid=auto` before playback**: Non-M3U file loads explicitly reset the video track to auto before loading, avoiding "no video track" state from previous audio-only plays.
- **`audio-display=embedded-first`**: Enabled MPV's embedded cover art rendering for audio files.
- **Orientation on audio launch**: `setOrientation()` checks `isKnownAudioLaunch()` immediately, before the track-list event settles — fixes the black-screen + landscape glitch on audio start.
- **Subtitle "Off" option**: Added an explicit "Off" choice in the player subtitle sheet so users can disable subtitles without cycling through all tracks.

### 🗑️ Folder Deletion Behavior
- **Media-only deletion (default)**: Deleting a folder now only removes audio/video files — other files (images, logs, documents) are left untouched.
- **"Delete folder + all contents" toggle**: New option in Appearance Settings > File Browser to switch back to full recursive deletion when needed.
- **Album View cleanup fix**: Fixed an issue where the last video file in a folder was deleted but the empty folder remained.

### 📱 Tablet & Display
- **Dynamic refresh rate**: The player can now dynamically adjust the display refresh rate to match the video frame rate for smoother playback.
- **Dual-pane navigation fix**: Resolved a state leak that could cause crashes when navigating in tablet dual-pane mode.
- **Dual-pane settings button hidden**: The redundant settings button is no longer shown in dual-pane mode on tablets.
- **Folder card height fix**: Manual grid column counts now align properly with folder card heights in dual-pane layout.

### 🔍 Settings Search
- **Search history**: Recently searched terms are saved and displayed for quick re-selection.
- **Search suggestions**: The search field now shows contextual suggestions as you type.
- **Reflection-based fallback**: Settings search can dynamically discover settings via reflection when static indices are incomplete.



## 1.5.0-preview.2 — Preview Release

### 📦 MpvLib Update
- Updated mpv library and its dependencies

### ⚡ Performance & Startup
- **Faster video open**: Opening a video file now uses `Dispatchers.Default` for the `playFile()` call — keeps the UI thread free and the player starts faster
- **Leaner startup sync**: The MPV directory sync no longer blanket-copies `shaders/` and `fonts/` on every launch — only config files, scripts, and `script-opts/` are synced upfront. Shaders referenced in `mpv.conf` are pulled on demand via `syncReferencedShaders()`, and fonts are handled by the font manager. This cuts down startup time noticeably, especially for users with large shader packs
- **Removed Dynamic Speed Overlay**: The old `SpeedControlSlider` (a full-size overlay with a dot-track slider) and `CompactSpeedIndicator` are gone. Hold-speed is now shown as a simple, clean text pill (e.g. "2x"). The `showDynamicSpeedOverlay` preference has been removed too — no more toggles, no more clutter
- **Snap-to-preset hold speed**: The hold-speed gesture now snaps to fixed presets (0.5x → 1x → 1.5x → 2x → 2.5x → 3x → 3.5x → 4x) instead of a free-form slider. The settings slider also snaps to these values, so what you see is what you get
- **Hold speed range capped**: Boost speed is now capped at 0.5x–4x range (previously went up to 6x)

### 📱 Tablet Dual-Pane Layouts
- **Folder view dual pane**: On tablets (600dp+ smallest screen width), you can now see your folder list on the left and a video list on the right — tap a folder, see its contents immediately beside it. A new "Dual Pane View" toggle in Appearance Settings lets you turn this on/off
- **Settings dual pane**: The Settings screen also gets a two-panel layout on tablets — the section list stays on the left, and the selected settings page opens on the right. The currently active section is highlighted with a subtle background
- **Back navigation in dual pane**: Pressing back in dual-pane mode deselects the folder/settings page instead of closing the screen

### 🎥 Player & Subtitles
- **Invert swipe subtitle direction**: New "Invert swipe subtitles direction" setting in Gesture Preferences. When enabled, swiping left-to-right seeks backward and right-to-left seeks forward — useful if you prefer the mirrored behaviour
- **Screenshot overhaul**: Screenshot filename templates got a proper rework:
  - `%wH`, `%wM`, `%wS`, `%wT` now use the **video playback position** (not the wall clock time) — so screenshot filenames actually match the video timestamp
  - New template placeholders: `%F` (filename without extension), `%P` (position as `HH:MM:SS.mmm`), `%p` (position as `HH:MM:SS`)
  - `%f` now resolves from the actual filename first, falling back to media-title — more reliable naming
- **Korean Jamo subtitle fix**: Downloaded subtitles that use Korean Jamo (composite characters) now go through NFC Unicode normalization. No more broken/corrupted Korean glyphs in subtitles
- **Subtitle search keyboard fix**: Added `android:windowSoftInputMode="adjustResize"` to PlayerActivity — the subtitle search dialog no longer gets hidden behind the on-screen keyboard
- **Subtitle persistence fix**: External subtitles and subtitle settings now survive across playback sessions more reliably. Added `addSubtitleSuspend()` (suspend version) for better coroutine handling during subtitle loading
- **Cache indicator fix**: The buffered range on the seekbar no longer double-counts the played portion — it now shows the correct remaining buffer ahead of the playhead
- **Color hex fix**: `toColorHexString()` now manually extracts ARGB components instead of using `Int.toHexString()` which produced wrong values for some colors

### 🛠️ Lua Script Improvements
- **Lua `require()` support**: Custom Lua scripts using `require()` can now find modules in `script-modules/` subdirectories. The app recursively syncs helper folders from `scripts/` to internal storage, so Lua's C-level `fopen()` can actually read them. Modules are also cleaned up when scripts are disabled in settings

### 🧹 Cleanup
- **Removed libpython binaries**: Deleted ~50 MB of unused `libpython_bin.so` files from all 4 architectures — they were never loaded by the app
- **Simplified MPV version display**: Removed the `cleanBundledMpvVersion()` hack in CrashActivity — MPV version now shows cleanly without needing string patching
- **Fonts folder no longer auto-set**: When changing the base storage root, the fonts folder preference is no longer blindly overwritten — it's only cleared if it was pointing at the old root. This prevents accidental font-folder resets

## 1.5.0-preview.1 — Preview Release

### 📦 MpvLib Update
- Updated mpv library and its dependencies 

### ⚡ Performance & Stability
- **Startup optimization**: Deferred cold-start DB initialization, grammar pre-load, and auto-update check to cut first-frame time significantly
- **Memory leak fixes**: Plugged 5 memory leaks across PlayerActivity (screen-state receiver), PlayerViewModel (LRU caches, temp subtitle cleanup), MediaPlaybackService (bitmap leak), MainActivity (unused scope), and NetworkLifecycleObserver (uncancelled coroutine)
- **UI smoothness**: Optimized seekbar spring animation, precomputed skip-segment colors, memoized immutable copies in PlayerControls, hoisted per-card preference collectors
- **Player crash fix**: Resolved a crash in PlayerActivity during stream initialization
- **Streaming optimizations**: Load network streams on `Dispatchers.IO` instead of Default to reduce CPU pool contention

### 🎨 UI/UX Improvements
- **Dynamic Grids**: Added responsive grid layouts — auto-adjusts column count based on screen width across FileSystemBrowser, FolderList, VideoList, Playlist, and RecentlyPlayed screens
- **Centered controls**: Play/pause and navigation buttons now center in portrait mode
- **Haptic feedback**: Sort dialog sliders now provide haptic ticks on snap
- **Slider layout**: Column sliders placed side-by-side for better space usage
- **Theme refresh**: Boosted container saturation and surface tints across all themes (light/dark) for vivid, non-washed-out appearance
- **Subtitle sheet redesign**: Inline SeriesSelectionControls directly in the OnlineSubtitleSearchSheet search row
- **Compressor back-press fix**: Video compressor overlay now handles system back press correctly

### 🎥 Player & Streaming
- **HLS/DASH streaming fix**: Direct media URLs (.m3u8/.mpd/.mp4/.ts) now bypass yt-dlp and use mpv's native ffmpeg HLS demuxer — these streams previously failed in yt-dlp's generic extractor
- **yt-dlp audio selection**: New audio track selector in the yt-dlp panel; preferred languages sanitization for better auto-selection
- **Collapsible advanced settings**: YtdlpSettingsScreen now organizes advanced options under collapsible sections
- **Console option**: Added Console toggle in MoreSheet stats rows — opens the mpv debug console via script-message
- **Subtitle hitbox fixes**: Dynamic hitbox detection for multi-line wrapped text; fixed hitbox under zoom/pan; lowered minimum subtitle scale limit
- **Subtitle loading fix**: Fixed subtitle loading and player overlay issues with IntentSubtitleLoadPolicy and M3uPlaybackPolicy
- **Negative brightness**: Added negative brightness range support in vertical sliders
- **Streaming overlays**: Fixed streaming playback overlays and thumbnail rendering

### 🔍 Anime Skip Provider
- New **Anime Skip** provider (api.anime-skip.com GraphQL) for intro/ending detection — searches via MAL ID, pairs consecutive timestamps
- Removed dead db.videasy.net TMDB mirror (providers now skip IMDB resolution without it)
- Cleaned up WyzieSearchRepository: removed fallback mirror logic and dead VideasyTmdbTrendingResponse

### 📁 Media Search Engine
- **New MediaSearchEngine**: Dedicated search engine for searching files/folders with optimized indexing
- **FolderListScreen integration**: Search across folders and videos using the new engine with VideoFolder references
- **Multiple refactors**: Cleaned up search logic, improved readability, and added Kotlin smart-casting clarifications

### 🐛 Bug Fixes
- Fixed mpv console ytdl_hook warnings (removed invalid 'all_subtitles' option)
- Fixed video-aspect-override deprecation warnings
- Resolved player crash during stream playback
- Fixed subtitle swipe gesture hitbox
- Fixed dynamic grid rendering edge cases

## 1.4.1-final

### Player, Playback & Stability
- Fixed stuttering and massive frame drops by optimizing video and screen synchronization.
- Fixed overlapping video frames/glitches when skipping rapidly through videos.
- Fixed picture-in-picture mode progress sync so your playback progress is correctly saved on exit.
- Prevented screen locking when opening the video's chapter list if the video has no chapters.
- Fixed visible screen rotation animation glitches when using the swipe-back gesture to exit the player.
- Adjusted player bottom controls bar padding to a more comfortable size.
- Cleaned up redundant HDR colorspace and tone mapping settings to avoid visual confusion.
- Fixed sudden crashes when loading or playing certain media files.
- Added outlined text with a black border to the Stats Page 6 overlay so text stays readable over any video content.

### Library, File Browser & Sizing
- Fixed playback, titles showing up as raw IDs (like `msf:1000`), missing thumbnails, and progress tracking for local M3U/M3U8 playlists.
- Added support for folder thumbnails in folder grid view (disabled by default, can be turned on in settings).
- Centralized file sorting options and improved title alignment in grid layouts.
- Fixed selection bar background in library list and grid layouts.
- Added Copy, Move, and Compress/Downscale options to the library selection bar.
- Fixed top toolbar delete button showing during folder selection to prevent accidental folder deletions.
- Fixed thumbnail taps so they toggle item selection instead of selecting ranges of items.
- Fixed library Media Info button to open details page instead of playing the file.

### Gestures & Dialogs
- Fixed subtitle swipe and pinch gesture detection zones so they are more responsive and natural to use.
- Added custom skip keywords for video openings and endings to automatically skip intros/outros.
- Redesigned the Sort and View Options Dialog to be more compact, with a collapsible "Fields" toggle section.
- Fixed bottom control bar icon scaling for smaller/larger screens.
- Fixed a visual glitch where the seekbar layout preview looked wavy.

### Network & Connections
- Added support for sending your playback progress to Jellyfin when playing videos externally.

- Many More Small QOL Fixes and Optimizations 

## 1.4.0

### Player And Seek Preview

- added the ThumbFast-style seek preview UI and thumbnail cache improvements.
- restored the legacy live-video seek preview path and added a Player setting to switch between ThumbFast thumbnail preview and legacy live seeking.
- tightened ThumbFast preview accuracy by using smaller preview time buckets and preventing stale thumbnail requests from replacing the newest preview frame.
- fixed glitched player vector icons and tightened subtitle/notch safe-area behavior.
-  improved launch smoothness and predictive back behavior.

### Subtitles

-  added subtitle font management in Settings > Subtitles: choose a fonts directory, see the selected source folder, reload fonts, clear the font cache, and select the default subtitle font.
-  added custom subtitle border styles and a shadow offset slider.
- **Arnab Sadhukhan** added subtitle zoom gestures.
- **Arnab Sadhukhan** optimized the subtitle pinch hit-zone for multi-line subtitles.
- **Arnab Sadhukhan** added horizontal swipe on subtitles to seek dialog lines.

### Browser, Library, And Storage

- redesigned the Media Info page to use a premium, tabbed Material 3 interface with beautiful overview stats, container metadata detail, track summaries, and customizable sharing.
- implemented the unified Media Library view mode.
- added multi-select range handling, folder copy/move/rename, and SMB mutex/reconnection guards.
- redesigned settings sections and moved progress-related options into cleaner places.
- fixed segmented button unchecked color handling.
- fixed Lua script copy behavior.

### Gestures And Quality Of Life

- **Arnab Sadhukhan** added the playlist swipe-up gesture in the player when swiped up from middle of screen now playlists open.
- **Arnab Sadhukhan** added auto-scroll to the currently selected theme.
- Removed Avif / Jpeg-Xl type images from the settings selection
- Added Font selection in the Subtitle Settings section.
- Fixed issue of Lua script when copying specific part Copied whole Lua script.
- added Expressive Scrollbar like in Pixel player 

## 1.3.9

> # 🚀 **CURL IS NOW SUPPORTED!** 
> ### ⚠️ **EXPERIMENTAL** — This is brand new and may or may not work properly on your device. ⚠️
> Lua and JavaScript scripts can now make HTTP requests through the new native libcurl bridge via JNI.
> Use it, break it, and report issues so we can make it stable!, see `MPVRX_CUSTOM_COMMANDS.d` for tutorial on how to use in Lua and JS.
>
> **What this means:** You can now write scripts that fetch data from the internet — APIs, subtitles, metadata, you name it — all through libcurl compiled directly into the app.

- AI support has been updated. Gemini is removed and OpenCode Zen AI is now available for AI rename, subtitle formatting, and subtitle translation.
- AI model lists now come from the provider APIs instead of a saved model list in the app. OpenRouter also marks free models using the pricing data returned by OpenRouter itself.
- Background playback is fixed so repeat keeps working after using the headphone button, and returning to the player no longer restarts the current stream from the beginning.
- Subtitle search has been updated for the latest Wyzie source changes.
- Added Hybrid Skip Markers. The player can now check IntroDB, TIDB, and AniSkip together and use whichever result is found first.
- Anime4K settings are now easier to use with a collapsible section in Decoder Preferences and also Added Optimization by Sunny Vishnu .
- Added a setting to show or hide Media Info from Android's share/open-with screen.
- Added documentation for custom Lua/JS player commands in `MPVRX_CUSTOM_COMMANDS.md`.

## 1.3.8

- **Integrated yt-dlp by [**SunnyVishnu3**](https://github.com/SunnyVishnu3)** — Added full yt-dlp integration for video watching audio/video from YouTube and other supported sites directly within the app (_Dont expect from me to add Download Functionality_). **Note: You need to download yt-dlp first (Settings > Advanced > yt-dlp Manager) before playing YouTube links — don't be clueless.**
- Fixed Issue of USer defined Colors Filters were not getting Saved and not getting applied through Mpv conf by [**SunnyVishnu3**](https://github.com/SunnyVishnu3)
- Fixed Gemini AI Error Generating / Translating Subs.
- Fixed Crashing issue of MpvRx , in a nutshell Ambient mode and Custom lua were not initialized in Sync causing to crash player sometimes
- That's all for Today Adiosss!!

## 1.3.7

- **Updated Wyzie subtitle API integration** — Synced with latest Wyzie API changes: added `ai` field for AI-translated subtitle detection, updated provider sources list (removed `subdl`, `podnapisi`, `ajatttools`; added `tvsubtitles`), and fixed TMDB endpoints to include API key authentication.
- **Material 3 Expressive Design** — Complete visual overhaul using Material 3 Expressive design system for a more modern, fluid, and engaging experience
- **Smoother Animations** — Replaced rigid linear transitions with spring-based physics animations throughout the app (navigation, controls, browser, dialogs)
- Added Voltage Battery Temperature And improved the style of Page 6 
- Added Optimized Ambient Mode with Eco Battery Saver Mode who want to take feel of Ambient without much Battery Impact
- Removed Dead Code and also Optimized some File Handling / Ui Rendering Operations
- **Settings export now stores app version**  exported XML files include the app version, so import dialogs show the correct version instead of "unknown".
- **HEVC 10bit thumbnails**  added a software-decoder fallback using Android's MediaCodec API. When the system can't decode a video frame (e.g. HEVC 10bit on devices without hardware support), the app now tries Google's software decoder before giving up. This means more thumbnails will show up on devices with limited codec support ( To be tested Propelry on unsupported device).
- **Fixed app icon on Android 16**  changed the adaptive icon background from transparent to opaque black so the icon doesn't disappear on launchers that don't handle transparency well.
- **User mpv.conf now has highest priority**  during player startup, your mpv.conf settings are re-applied after all app defaults so they always take precedence. but some of the Hardcoded things doesnt change like for example `sid, aid`.
- 

## 1.3.6

- **Six AI providers, one gorgeous settings page**  OpenAI, Anthropic, OpenRouter, and Together joined Groq and Gemini in a completely redesigned UI. Every provider gets its own API key, every single model is visible (free ones get a bold green badge), and the new searchable model picker sorts free models to the top. The offline model experience got a premium card-based overhaul too  tiers, speed/translation badges, device recommendations, DeepSeek-R1 support, reasoning toggles, and a benchmark button for downloaded models. One-tap download, delete, and switch between models without ever leaving the screen.

- **Subtitle translation**  SUPPORTS ASS Subs Translation tooooooooo..... , you can now configure your target languages once in settings. One language means one tap to translate. Two or more means a clean picker showing only the languages you chose. Translation progress appears right on the video screen (even with the sheet closed), partially translated subs survive restarts, and a red X lets you cancel mid-translation instantly. When using local models, the system automatically picks the best downloaded model for each language, keeps it warm between chunks, and never runs two local AI jobs at once.

- **Generate subtitles from video audio**  **_(EXPERIMENTAL)_** This is work in progress might not work Don't baby Cry that this shit aint working ,i ain't getting paid enough to implement this whole heartedly , so what it does is -> one tap generates subtitles using the audio you're already playing. Media3 extraction feeds Groq, Gemini, or offline Whisper, and the resulting SRT/VTT saves automatically.

- **Smarter AI across the board**  reasoning tags are automatically stripped from final results, token limits prevent stalls in heavy tasks, and every AI feature (rename, formatting, translation) comes with customizable prompts that fall back gracefully to built-in instructions.

- **Real-time subtitle toggle**  new on/off switch in AI settings to control real-time subtitle generation from audio. When off, the indicator and generate button are hidden from the player.

- **AI features respect the master switch**  turning off AI Integration now hides all AI indicators (translation, real-time subs) and buttons (generate, translate, format) from the player view. Renamed "AI Subtitle Search Formatting" to "AI Search" for clarity.


## 1.3.5

- **Removed Play Store and F-Droid build variants**  streamlined to a single `standard` flavor with full update support and all features enabled.
- **Revamped README**  comprehensive feature documentation organized by category, UPI QR code and Buy Me a Coffee links in the Support section.
- **SMB Network Thumbnail Generation**  fixed thumbnail generation for SMB shares through Codex AI (Beta).
- **Bulk AI Rename**  rename multiple files at once using Gemini or Groq with concurrency limiting and edge case handling.
- **AI Subtitle Translation**  translate subtitles using AI providers with custom prompts, progress indication, and user preference management.
- **AI Subtitle Translation Enhancements**  in-house developed translation pipeline with fully customizable prompts and per-user preference overrides.

## 1.3.4

- Capped generated thumbnails to safer preview sizes so large videos do not waste memory while browsing.
- Improved MKV/WebM thumbnail handling, including embedded artwork and smarter fallback frames.
- Cleaned old thumbnail cache paths when clearing thumbnail cache.
- Fixed the About and crash info screen showing `UNKNOWN` in the bundled mpv version.
- Updated Gradle, Kotlin, Compose, Koin, Navigation 3, AndroidX, and related dependency versions through the version catalog.
- Added SUbHub MpvRx specific Subtitle Fetching nd Downloading featured developed by me
- Added Video COmpresser Overlay in Tree Mode also
- Cleaned up codebase and Improved Playback bottlenecks
- Added Window Offset to prevent Camera notch overlap issues


## 1.3.3

- Fixed Background Playback and Pip issues 
- Anime4K should feel much smoother now. The player now uses the clean six-preset Anime4K flow from the reference app and avoids piling old shader work on top of the new preset when you switch modes.
- Anime4K is still off by default, but when you turn it on the picker is simpler: Off, A, B, C, A+, B+, and C+.
- Moved the Fast / Balanced / High Anime4K choice into Decoder settings, with Balanced as the default.
- Removed frame interpolation because it added a lot of GPU load and did not add enough real value.
- Removed the old OneThird and Halfway thumbnail choices.
- Removed the unused old player screen path.
- Cleaned up the track sheets so audio, subtitle, chapter, decoder, and online subtitle lists no longer depend on the old generic sheet.
- Removed SubDL from subtitle search sources.
- Network streaming is now opt-in instead of being enabled on a fresh install.
- HDR and Ambient controls are no longer placed on the default player buttons, so heavy visual extras stay out of the way unless you add them yourself.
- Turning HDR on now starts with Linear HDR by default.
- The app now does less background media scanning and cache cleanup on startup, which should help large libraries open with less churn.
- Added new MpvLib File with Some Optimization and Removing Deprecated Andorid Versions
- Thumbnails are now Loaded Faster and more Precisly

## 1.3.2

### HDR hdr-toys Pipeline

- Replaced the old 3-mode HDR system (Off / SDR with HDR / Normal HDR) with a proper shader-based pipeline powered by [hdr-toys](https://github.com/natural-harmonia-gropius/hdr-toys).
- Four HDR modes are now available: **BT.2100 PQ** (HDR10), **BT.2100 HLG**, **BT.2020**, and **Linear HDR** (mpv-native, no shaders).
- 77 GLSL shaders are bundled in the app and copied to the mpv config directory on first use  no manual setup required.
- The HDR panel no longer shows an "Off" option. Off is the default and is toggled by the HDR button; the panel only presents the four active modes.
- Selecting a mode while GPU Next + Vulkan is unavailable shows a clear error pill and falls back to Off safely.
- Added `boostSdrToHdr` preference (used by the Linear HDR path).
- `HdrToysManager` cleanly removes all hdr-toys shaders when switching to Off or when the pipeline is not ready, so no stale shaders leak between sessions.

### Thermal & Battery Improvements

- Added `ThermalMonitor`  samples `PowerManager.getThermalHeadroom()` (Android 11+) every 10 seconds during playback.
- Ambient shader sample budget is automatically capped based on thermal headroom: 8 samples (severe), 12 (moderate), 18 (mild), uncapped (cool).
- Anime4K is proactively downgraded to C/Fast when thermal headroom drops below 40%, before frame drops even start.
- Ambient shader recompilation is now skipped when all parameters are identical to the last compiled version  reduces unnecessary GPU stutter on orientation changes and no-op callbacks.
- Removed redundant dual position polling: the event-driven `time-pos` observer and the polling loop were both updating the same StateFlow, causing double seek-bar recompositions on every MPV event.
- Background playback position poll interval halved from 250 ms to 500 ms when controls are not visible, cutting idle JNI wake-ups by 50%.

### Stats Page 6  Fixes

- **GPU estimate bar fixed**: was using cumulative drop + delay totals that drifted to 100% after long sessions and added a fixed FPS-proportional baseline (120fps with zero drops showed 70% GPU load). Now uses per-second delta counts relative to the current frame rate  0 drops = 0%, all frames dropped = 100%.
- **CPU label corrected**: relabelled from "CPU Usage" to "App CPU (this process)" to accurately reflect that `getElapsedCpuTime()` measures only MpvRx's own process, not the whole device.
- **Frame drop text now shows per-second deltas** alongside the all-time totals, so you can tell current rendering pressure at a glance.
- **Pause-aware poll backoff**: the stats loop backs off from 1 s to 2 s intervals when playback is paused, cutting pointless JNI calls when metrics are static.

### Gesture & Action Overlay Toggles

- Added a new **"Gesture & Action Overlays"** section in Player Settings with seven independent on/off switches:
  - **Volume slider overlay**  vertical pill shown during volume swipe
  - **Brightness slider overlay**  vertical pill shown during brightness swipe
  - **Hold speed overlay**  speed badge and slider shown during long-press speed boost
  - **Aspect ratio feedback**  pill shown when cycling aspect ratio
  - **Zoom level feedback**  pill shown when pinching to zoom
  - **Repeat & shuffle feedback**  pill shown when toggling repeat or shuffle
  - **Action feedback pills**  brief text pills from custom buttons, ambient toggle, subtitle drag, and Lua/JS scripts
- All overlays default to **on**, so existing behaviour is unchanged until the user opts out.
- Disabling an overlay suppresses only the visual pill  the underlying gesture action (volume change, speed change, etc.) still happens normally.

## 1.3.1

- Update FFmpeg to n8.1 (latest stable)
- Update Android SDK to 36, build tools 36.0.0
- Update Kotlin to 2.1.21, Gradle to 8.11.1
- Update dependencies: unibreak 6.2, harfbuzz 11.5.0, fribidi 1.0.17, freetype 2.13.4, mbedtls 3.6.5
- Add mujs 1.3.5 support for JavaScript scripting inside mpv
- JavaScript (.js) scripts are now supported alongside Lua scripts, with "Scripts (Lua / JS)" kept to the main section titles.
- Script editor now uses the native Sora editor with TextMate syntax highlighting for Lua and JavaScript.
- Script editor includes a chip toggle to choose between `.lua` and `.js` file extensions when creating or editing scripts.
- Custom player buttons can now run either Lua or JavaScript, with language selection per button and import/export support.
- Long-pressing the HDR button now opens an HDR Output panel with Off, SDR with HDR, and Normal HDR modes.
- Media title resolution improved: MPV's resolved title is preferred for non-direct-media URLs and when the current filename looks like a generic route (e.g., `/watch`, `/stream`).
- Updated mpv library dependency from `mpv-android-lib-v0.0.1.aar` to `mpvlib.aar` and removed the old AAR.
- Added Multiple new provider to Wyzie subtitle sources.
- PiP and background playback now save the latest watched position instead of returning to the timestamp from before PiP started.
- Video lists refresh playback progress as soon as the saved position changes, so returning from the player shows the current progress.
- Folder thumbnails now begin rendering immediately when a folder opens, while still using cached thumbnail data first.

## 1.3.0

- The project now carries the `MpvRx` name across the app, docs, and release files.
- Tree View `NEW` labels now work properly and update as you watch.
- Single-child folders now flatten automatically so you reach files faster.
- Subtitle matching is smarter and better at finding subtitles that line up.
- Cached library data shows up first, then refreshes quietly in the background.
- Browser updates now react to changes instead of constantly polling.
- The player now remembers your chosen aspect ratio.
- Seeking feels steadier and cleanup after playback is smoother.
- Ambient mode and Lua scripting were reverted.
- The settings page was revamped.
- New tab and video animations were added.
- Icons were refreshed across the app.
- Network and playlist behavior was cleaned up.
- Folder pinning was added.
- A video size downgrade option was added in the video editing section.
- Page 6 was added to More Sheet for battery usage and extra system info.
- A new status icon row can show network speed, battery percentage, and time.

## 1.2.9

- Library scanning became faster and more dependable.
- Subtitle search got a noticeable improvement.
- Theme picking now jumps to the active theme more cleanly.
- Ambient mode got another round of polish and fixes.

## 1.2.8-hotfix

- A rough ambient mode change was rolled back to keep playback stable.
- The zoom sheet layout was cleaned up.
- Playback profiles became easier to manage.

## 1.2.8

- Background playback became more dependable.
- File rename and delete flows became safer and clearer.
- Custom buttons load more reliably.
- Play Store and F-Droid releases were cleaned up.
- The update and media tools were reorganized.

## 1.2.7

- The seekbar was cleaned up and accidental swipe behavior was reduced.
- F-Droid builds were added.
- Release packaging and signing became more reliable.

## 1.2.6

- Background playback and notifications became steadier.
- Filter presets and video quality controls were improved.
- External subtitle scaling and positioning were fixed.

## 1.2.5

- Video scaling and smooth motion options were added.
- Thumbnail generation became faster and more consistent.
- Browser spacing and player gestures were cleaned up.

## 1.2.4

- New videos now show a `NEW` label more reliably.
- Rotated videos and aspect handling were improved.
- Subtitle styling controls were expanded.
- Playlist order and storage permission handling were cleaned up.

## 1.2.3

- Network thumbnails became optional.
- Recently Played works better with network items.
- Thumbnail loading became faster.
- Browser navigation and floating actions became more consistent.

## 1.2.2

- Repeat and shuffle now stay the way you left them.
- Subtitle preferences now carry across playback more reliably.
- Hardware decoding falls back more safely on tricky devices.
- Player rotation and status bar behavior were improved.
- SMB playback became more dependable.

## 1.2.1

- Grid mode arrived for folders and videos.
- Scroll position is remembered when you come back.
- Thumbnail visibility can be toggled.
- A background playback edge case was fixed.

## 1.2.0

- The app got a major Material 3 refresh.
- Settings were reorganized into a cleaner card layout.
- Local M3U playlists were added.
- Recently Played got pull-to-refresh.
- Track and subtitle handling became smarter.

## 1.1.0

- Network browsing arrived for SMB, FTP, and WebDAV.
- File manager mode and breadcrumb navigation were added.
- Playlist mode became more useful.
- Recently Played learned how to handle playlists too.
- The project website and screenshots were refreshed.

## 1.0.0

- First public release.
- Media info viewing and sharing were added.
- F-Droid release work was prepared.
