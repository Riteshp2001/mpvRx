package app.gyrolet.mpvrx.preferences

import androidx.compose.runtime.Composable
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icons

/**
 * Represents a customizable button in the player controls.
 * Now includes an icon for the preference UI.
 */
enum class PlayerButton(
  val icon: AppIcon,
) {
  BACK_ARROW(Icons.RoundedFilled.ArrowBack),
  VIDEO_TITLE(Icons.RoundedFilled.Title),
  BOOKMARKS_CHAPTERS(Icons.RoundedFilled.Bookmarks),
  PLAYBACK_SPEED(Icons.RoundedFilled.Speed),
  DECODER(Icons.RoundedFilled.DeveloperBoard),
  HDR_MODE(Icons.RoundedFilled.HdrOff),
  SCREEN_ROTATION(Icons.RoundedFilled.ScreenRotation),
  FRAME_NAVIGATION(Icons.RoundedFilled.Screenshot),
  VIDEO_ZOOM(Icons.RoundedFilled.ZoomIn),
  PICTURE_IN_PICTURE(Icons.RoundedFilled.PictureInPictureAlt),
  CAST(Icons.RoundedFilled.Cast),
  ASPECT_RATIO(Icons.RoundedFilled.AspectRatio),
  LOCK_CONTROLS(Icons.RoundedFilled.LockOpen),
  AUDIO_TRACK(Icons.RoundedFilled.Audiotrack),
  SUBTITLES(Icons.RoundedFilled.Subtitles),
  MORE_OPTIONS(Icons.RoundedFilled.MoreVert),
  CURRENT_CHAPTER(Icons.RoundedFilled.Bookmarks), // <-- CHANGED ICON
  REPEAT_MODE(Icons.RoundedFilled.Repeat),
  SHUFFLE(Icons.RoundedFilled.Shuffle),
  MIRROR(Icons.RoundedFilled.Flip),
  VERTICAL_FLIP(Icons.RoundedFilled.Flip),
  AB_LOOP(Icons.RoundedFilled.Repeat),
  CUSTOM_SKIP(Icons.RoundedFilled.FastForward),
  BACKGROUND_PLAYBACK(Icons.RoundedFilled.Headset),
  AMBIENT_MODE(Icons.RoundedFilled.BlurOff),
  TIME_NETWORK(Icons.RoundedFilled.AccessTime),
  NONE(Icons.RoundedFilled.Bookmarks),
}

/**
 * A list of all buttons that the user can choose from in the customization menu.
 * Excludes NONE (placeholder) and constant buttons (BACK_ARROW, VIDEO_TITLE).
 */
val allPlayerButtons =
  PlayerButton.values().filter {
    it != PlayerButton.NONE &&
      it != PlayerButton.BACK_ARROW &&
      it != PlayerButton.VIDEO_TITLE
  }

/**
 * Gets the human-readable label for a player button.
 * TODO: You must add these string resources to your `strings.xml` file.
 */
@Composable
fun getPlayerButtonLabel(button: PlayerButton): String =
  when (button) {
    PlayerButton.BACK_ARROW -> "Back Arrow" // stringResource(R.string.btn_label_back)
    PlayerButton.VIDEO_TITLE -> "Video Title" // stringResource(R.string.btn_label_title)
    PlayerButton.BOOKMARKS_CHAPTERS -> "Chapters / Bookmarks" // stringResource(R.string.btn_label_bookmarks)
    PlayerButton.PLAYBACK_SPEED -> "Playback Speed" // stringResource(R.string.btn_label_speed)
    PlayerButton.DECODER -> "Decoder" // stringResource(R.string.btn_label_decoder)
    PlayerButton.HDR_MODE -> "HDR Screen Output"
    PlayerButton.SCREEN_ROTATION -> "Screen Rotation" // stringResource(R.string.btn_label_rotation)
    PlayerButton.FRAME_NAVIGATION -> "Frame Navigation" // stringResource(R.string.btn_label_frame_nav)
    PlayerButton.VIDEO_ZOOM -> "Video Zoom" // stringResource(R.string.btn_label_zoom)
    PlayerButton.PICTURE_IN_PICTURE -> "Picture-in-Picture" // stringResource(R.string.btn_label_pip)
    PlayerButton.CAST -> "Cast"
    PlayerButton.ASPECT_RATIO -> "Aspect Ratio" // stringResource(R.string.btn_label_aspect)
    PlayerButton.LOCK_CONTROLS -> "Lock Controls" // stringResource(R.string.btn_label_lock)
    PlayerButton.AUDIO_TRACK -> "Audio Track" // stringResource(R.string.btn_label_audio)
    PlayerButton.SUBTITLES -> "Subtitles" // stringResource(R.string.btn_label_subtitles)
    PlayerButton.MORE_OPTIONS -> "More Options" // stringResource(R.string.btn_label_more)
    PlayerButton.CURRENT_CHAPTER -> "Current Chapter" // stringResource(R.string.btn_label_chapter)
    PlayerButton.REPEAT_MODE -> "Repeat Mode" // stringResource(R.string.btn_label_repeat_mode)
    PlayerButton.SHUFFLE -> "Shuffle" // stringResource(R.string.btn_label_shuffle)
    PlayerButton.MIRROR -> "Horizontal Flip"
    PlayerButton.VERTICAL_FLIP -> "Vertical Flip"
    PlayerButton.AB_LOOP -> "A-B Loop"
    PlayerButton.CUSTOM_SKIP -> "Custom Skip"
    PlayerButton.BACKGROUND_PLAYBACK -> "Background Playback"
    PlayerButton.AMBIENT_MODE -> "Ambience Mode"
    PlayerButton.TIME_NETWORK -> "Time + Network"
    PlayerButton.NONE -> "None"
  }
