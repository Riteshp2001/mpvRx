package app.gyrolet.mpvrx.ui.icons

import androidx.annotation.DrawableRes
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.*
import com.composables.icons.materialsymbols.roundedfilled.R as MaterialSymbolsR

@Suppress("MemberVisibilityCanBePrivate")
object Icons {
  private object Shared {
    val AccessTime by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Schedule) }
    val AccountBalance by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Account_balance) }
    val AccountTree by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Account_tree) }
    val Add by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Add) }
    val AddCircle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Add_circle) }
    val AlignVerticalCenter by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Align_vertical_center) }
    val Article by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Article) }
    val ArrowBack by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.West) }
    val ArrowBackClassic by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Arrow_back) }
    val ArrowBackIos by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Arrow_back_ios) }
    val ArrowBackIosNew by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Arrow_back_ios_new) }
    val ArrowDropDown by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Arrow_drop_down) }
    val ArrowForward by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.East) }
    val ArrowLeftAlt by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Arrow_left_alt) }
    val AspectRatio by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Aspect_ratio) }
    val Audiotrack by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Music_note) }
    val AutoAwesome by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Auto_awesome) }
    val AutoFixHigh by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Auto_fix_high) }
    val Aperture by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Shutter_speed) }
    val AvTimer by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Av_timer) }
    val Block by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Block) }
    val BlurOff by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Blur_off) }
    val BlurOn by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Blur_on) }
    val Bookmarks by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Bookmarks) }
    val BorderColor by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Border_color) }
    val BorderStyle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Border_style) }
    val BrandFamily by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Brand_family) }
    val Brightness6 by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Brightness_6) }
    val BrightnessHigh by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Brightness_high) }
    val BrightnessLow by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Brightness_empty) }
    val BrightnessMedium by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Brightness_medium) }
    val BringYourOwnIp by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Bring_your_own_ip) }
    val BugReport by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Bug_report) }
    val CalendarToday by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Calendar_today) }
    val Camera by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Camera) }
    val CameraAlt by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Photo_camera) }
    val Cancel by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Cancel) }
    val CatchingPokemon by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Pets) }
    val Cast by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Cast) }
    val Check by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Check) }
    val CheckCircle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Check_circle) }
    val Checklist by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Checklist) }
    val ChevronLeft by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Chevron_left) }
    val ChevronRight by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Chevron_right) }
    val Clear by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Close) }
    val Close by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Close) }
    val CloudDownload by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Cloud_download) }
    val Code by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Code) }
    val ContentCopy by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Content_copy) }
    val ContentPaste by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Content_paste) }
    val CreateNewFolder by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Create_new_folder) }
    val CurrencyRupee by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Currency_rupee) }
    val Delete by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Delete) }
    val DeveloperBoard by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Developer_board) }
    val Download by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Download) }
    val DragHandle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Drag_handle) }
    val DriveFileMove by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Drive_file_move) }
    val DriveFileMoveOutline by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Drive_file_move_outline) }
    val DriveFileRenameOutline by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Drive_file_rename_outline) }
    val DriveFolderUpload by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Drive_folder_upload) }
    val Edit by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Edit) }
    val EditOff by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Edit_off) }
    val ExpandLess by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Expand_less) }
    val ExpandMore by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Expand_more) }
    val FastForward by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Fast_forward) }
    val FastRewind by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Fast_rewind) }
    val FeaturedPlayList by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Featured_play_list) }
    val FileDownload by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.File_download) }
    val FileOpen by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.File_open) }
    val FileUpload by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.File_upload) }
    val FitScreen by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Fit_screen) }
    val Flip by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Flip) }
    val Folder by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Folder) }
    val FolderOff by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Folder_off) }
    val FolderOpen by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Folder_open) }
    val FormatAlignCenter by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_align_center) }
    val FormatAlignJustify by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_align_justify) }
    val FormatAlignLeft by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_align_left) }
    val FormatAlignRight by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_align_right) }
    val FormatBold by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_bold) }
    val FormatClear by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_clear) }
    val FormatColorFill by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_color_fill) }
    val FormatColorReset by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_color_reset) }
    val FormatColorText by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_color_text) }
    val FormatItalic by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_italic) }
    val FormatSize by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Format_size) }
    val FrameInspect by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Frame_inspect) }
    val Gesture by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Gesture) }
    val Gradient by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Gradient) }
    val Grain by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Grain) }
    val GridView by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Grid_view) }
    val Headset by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Headphones) }
    val HdrOff by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Hdr_off) }
    val HdrOn by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Hdr_on) }
    val History by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.History_2) }
    val NewReleases by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.New_releases) }
    val Home by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Home) }
    val Info by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Info) }
    val InsertDriveFile by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Description) }
    val KeyboardArrowDown by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Keyboard_arrow_down) }
    val KeyboardArrowLeft by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Keyboard_arrow_left) }
    val KeyboardArrowRight by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Keyboard_arrow_right) }
    val KeyboardArrowUp by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Keyboard_arrow_up) }
    val Language by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Language) }
    val Link by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Link) }
    val Translate by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Translate) }
    val LinkOff by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Link_off) }
    val Lock by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Lock) }
    val LockOpen by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Lock_open) }
    val ListAlt by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.List_alt) }
    val Memory by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Memory) }
    val MonetizationOn by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Monetization_on) }
    val MoreTime by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.More_time) }
    val MoreVert by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.More_vert) }
    val Movie by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Movie) }
    val NotInterested by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Block) }
    val Opacity by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Opacity) }
    val Palette by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Palette) }
    val Pause by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Pause) }
    val PictureInPictureAlt by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Picture_in_picture_alt) }
    val PlayArrow by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Play_arrow) }
    val PlayCircle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Play_circle) }
    val PlaylistAddCheck by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Playlist_add_check) }
    val PlaylistAddCircle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Playlist_add_circle) }
    val SmartDisplay by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Smart_display) }
    val Videocam by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Videocam) }
    val PlaylistAdd by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Playlist_add) }
    val PlaylistPlay get() = PlayArrow
    val PushPin by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Push_pin) }
    val QueueMusic by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Queue_music) }
    val Refresh by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Refresh) }
    val Remove by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Remove) }
    val RemoveCircle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Do_not_disturb_on) }
    val Repeat by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Repeat) }
    val RepeatOn by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Repeat_on) }
    val RepeatOne by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Repeat_one) }
    val ResetIso by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Reset_iso) }
    val Restore by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Undo) }
    val RoundedCorner by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Rounded_corner) }
    val ScreenRotation by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Screen_rotation) }
    val Screenshot by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Screenshot) }
    val SdCard by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Sd_card) }
    val Search by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Search) }
    val Settings by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Settings) }
    val Shadow by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Shadow) }
    val Share by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Share) }
    val Shuffle by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Shuffle) }
    val ShuffleOn by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Shuffle_on) }
    val SortByAlpha by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Sort_by_alpha) }
    val SignalWifiStatusbarConnectedNoInternet4 by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Signal_wifi_statusbar_not_connected) }
    val SkipNext by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Skip_next) }
    val SkipPrevious by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Skip_previous) }
    val Slideshow by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Slideshow) }
    val Speed by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Speed) }
    val Subtitles by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Subtitles) }
    val SwapVert by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Swap_vert) }
    val SystemUpdate by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.System_update) }
    val Thermostat by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Thermostat) }
    val Timer by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Timer) }
    val Terminal by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Terminal) }
    val Title by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Title) }
    val Tune by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Tune) }
    val Update by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Update) }
    val Usb by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Usb) }
    val VideoLibrary by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Video_library) }
    val ViewAgenda by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.View_agenda) }
    val ViewArray by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.View_array) }
    val ViewComfy by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.View_comfy) }
    val ViewList by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.View_list) }
    val ViewModule by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.View_module) }
    val Vignette by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Vignette) }
    val ViewQuilt by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.View_quilt) }
    val VolumeDownAlt by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Volume_down_alt) }
    val VolumeDown by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Volume_down) }
    val VolumeMute by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Volume_mute) }
    val VolumeOff by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Volume_off) }
    val VolumeUp by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Volume_up) }
    val RingVolume by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Ring_volume) }
    val Warning by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Warning) }
    val West by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.West) }
    val WbSunny by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Wb_sunny) }
    val ZoomIn by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Zoom_in) }
    val ZoomOutMap by lazy(LazyThreadSafetyMode.NONE) { AppIcon(MaterialSymbols.RoundedFilled.Zoom_out_map) }
  }

  object RoundedFilled {
    val AccessTime get() = Shared.AccessTime
    val AccountBalance get() = Shared.AccountBalance
    val AccountTree get() = Shared.AccountTree
    val Add get() = Shared.Add
    val AddCircle get() = Shared.AddCircle
    val AlignVerticalCenter get() = Shared.AlignVerticalCenter
    val Article get() = Shared.Article
    val ArrowBack get() = Shared.ArrowBack
    val ArrowBackClassic get() = Shared.ArrowBackClassic
    val ArrowBackIos get() = Shared.ArrowBackIos
    val ArrowBackIosNew get() = Shared.ArrowBackIosNew
    val ArrowDropDown get() = Shared.ArrowDropDown
    val ArrowForward get() = Shared.ArrowForward
    val ArrowLeftAlt get() = Shared.ArrowLeftAlt
    val AspectRatio get() = Shared.AspectRatio
    val Audiotrack get() = Shared.Audiotrack
    val AutoAwesome get() = Shared.AutoAwesome
    val AutoFixHigh get() = Shared.AutoFixHigh
    val Aperture get() = Shared.Aperture
    val AvTimer get() = Shared.AvTimer
    val Block get() = Shared.Block
    val BlurOff get() = Shared.BlurOff
    val BlurOn get() = Shared.BlurOn
    val Bookmarks get() = Shared.Bookmarks
    val BorderColor get() = Shared.BorderColor
    val BorderStyle get() = Shared.BorderStyle
    val BrandFamily get() = Shared.BrandFamily
    val Brightness6 get() = Shared.Brightness6
    val BrightnessHigh get() = Shared.BrightnessHigh
    val BrightnessLow get() = Shared.BrightnessLow
    val BrightnessMedium get() = Shared.BrightnessMedium
    val BringYourOwnIp get() = Shared.BringYourOwnIp
    val BugReport get() = Shared.BugReport
    val CalendarToday get() = Shared.CalendarToday
    val Camera get() = Shared.Camera
    val CameraAlt get() = Shared.CameraAlt
    val Cancel get() = Shared.Cancel
    val CatchingPokemon get() = Shared.CatchingPokemon
    val Cast get() = Shared.Cast
    val Check get() = Shared.Check
    val CheckCircle get() = Shared.CheckCircle
    val Checklist get() = Shared.Checklist
    val ChevronLeft get() = Shared.ChevronLeft
    val ChevronRight get() = Shared.ChevronRight
    val Clear get() = Shared.Clear
    val Close get() = Shared.Close
    val CloudDownload get() = Shared.CloudDownload
    val Code get() = Shared.Code
    val ContentCopy get() = Shared.ContentCopy
    val ContentPaste get() = Shared.ContentPaste
    val CreateNewFolder get() = Shared.CreateNewFolder
    val CurrencyRupee get() = Shared.CurrencyRupee
    val Delete get() = Shared.Delete
    val DeveloperBoard get() = Shared.DeveloperBoard
    val Download get() = Shared.Download
    val DragHandle get() = Shared.DragHandle
    val DriveFileMove get() = Shared.DriveFileMove
    val DriveFileMoveOutline get() = Shared.DriveFileMoveOutline
    val DriveFileRenameOutline get() = Shared.DriveFileRenameOutline
    val DriveFolderUpload get() = Shared.DriveFolderUpload
    val Edit get() = Shared.Edit
    val EditOff get() = Shared.EditOff
    val ExpandLess get() = Shared.ExpandLess
    val ExpandMore get() = Shared.ExpandMore
    val FastForward get() = Shared.FastForward
    val FastRewind get() = Shared.FastRewind
    val FeaturedPlayList get() = Shared.FeaturedPlayList
    val FileDownload get() = Shared.FileDownload
    val FileOpen get() = Shared.FileOpen
    val FileUpload get() = Shared.FileUpload
    val FitScreen get() = Shared.FitScreen
    val Flip get() = Shared.Flip
    val Folder get() = Shared.Folder
    val FolderOff get() = Shared.FolderOff
    val FolderOpen get() = Shared.FolderOpen
    val FormatAlignCenter get() = Shared.FormatAlignCenter
    val FormatAlignJustify get() = Shared.FormatAlignJustify
    val FormatAlignLeft get() = Shared.FormatAlignLeft
    val FormatAlignRight get() = Shared.FormatAlignRight
    val FormatBold get() = Shared.FormatBold
    val FormatClear get() = Shared.FormatClear
    val FormatColorFill get() = Shared.FormatColorFill
    val FormatColorReset get() = Shared.FormatColorReset
    val FormatColorText get() = Shared.FormatColorText
    val FormatItalic get() = Shared.FormatItalic
    val FormatSize get() = Shared.FormatSize
    val FrameInspect get() = Shared.FrameInspect
    val Gesture get() = Shared.Gesture
    val Gradient get() = Shared.Gradient
    val Grain get() = Shared.Grain
    val GridView get() = Shared.GridView
    val Headset get() = Shared.Headset
    val HdrOff get() = Shared.HdrOff
    val HdrOn get() = Shared.HdrOn
    val History get() = Shared.History
    val NewReleases get() = Shared.NewReleases
    val Home get() = Shared.Home
    val Info get() = Shared.Info
    val InsertDriveFile get() = Shared.InsertDriveFile
    val KeyboardArrowDown get() = Shared.KeyboardArrowDown
    val KeyboardArrowLeft get() = Shared.KeyboardArrowLeft
    val KeyboardArrowRight get() = Shared.KeyboardArrowRight
    val KeyboardArrowUp get() = Shared.KeyboardArrowUp
    val Language get() = Shared.Language
    val Link get() = Shared.Link
    val Translate get() = Shared.Translate
    val LinkOff get() = Shared.LinkOff
    val Lock get() = Shared.Lock
    val LockOpen get() = Shared.LockOpen
    val ListAlt get() = Shared.ListAlt
    val Memory get() = Shared.Memory
    val MonetizationOn get() = Shared.MonetizationOn
    val MoreTime get() = Shared.MoreTime
    val MoreVert get() = Shared.MoreVert
    val Movie get() = Shared.Movie
    val NotInterested get() = Shared.NotInterested
    val Opacity get() = Shared.Opacity
    val Palette get() = Shared.Palette
    val Pause get() = Shared.Pause
    val PictureInPictureAlt get() = Shared.PictureInPictureAlt
    val PlayArrow get() = Shared.PlayArrow
    val PlayCircle get() = Shared.PlayCircle
    val PlaylistAddCheck get() = Shared.PlaylistAddCheck
    val PlaylistAddCircle get() = Shared.PlaylistAddCircle
    val SmartDisplay get() = Shared.SmartDisplay
    val Videocam get() = Shared.Videocam
    val PlaylistAdd get() = Shared.PlaylistAdd
    val PlaylistPlay get() = Shared.PlaylistPlay
    val PushPin get() = Shared.PushPin
    val QueueMusic get() = Shared.QueueMusic
    val Refresh get() = Shared.Refresh
    val Remove get() = Shared.Remove
    val RemoveCircle get() = Shared.RemoveCircle
    val Repeat get() = Shared.Repeat
    val RepeatOn get() = Shared.RepeatOn
    val RepeatOne get() = Shared.RepeatOne
    val ResetIso get() = Shared.ResetIso
    val Restore get() = Shared.Restore
    val RoundedCorner get() = Shared.RoundedCorner
    val ScreenRotation get() = Shared.ScreenRotation
    val Screenshot get() = Shared.Screenshot
    val SdCard get() = Shared.SdCard
    val Search get() = Shared.Search
    val Settings get() = Shared.Settings
    val Shadow get() = Shared.Shadow
    val Share get() = Shared.Share
    val Shuffle get() = Shared.Shuffle
    val ShuffleOn get() = Shared.ShuffleOn
    val SortByAlpha get() = Shared.SortByAlpha
    val SignalWifiStatusbarConnectedNoInternet4 get() = Shared.SignalWifiStatusbarConnectedNoInternet4
    val SkipNext get() = Shared.SkipNext
    val SkipPrevious get() = Shared.SkipPrevious
    val Slideshow get() = Shared.Slideshow
    val Speed get() = Shared.Speed
    val Subtitles get() = Shared.Subtitles
    val SwapVert get() = Shared.SwapVert
    val SystemUpdate get() = Shared.SystemUpdate
    val Thermostat get() = Shared.Thermostat
    val Timer get() = Shared.Timer
    val Terminal get() = Shared.Terminal
    val Title get() = Shared.Title
    val Tune get() = Shared.Tune
    val Update get() = Shared.Update
    val Usb get() = Shared.Usb
    val VideoLibrary get() = Shared.VideoLibrary
    val ViewAgenda get() = Shared.ViewAgenda
    val ViewArray get() = Shared.ViewArray
    val ViewComfy get() = Shared.ViewComfy
    val ViewList get() = Shared.ViewList
    val ViewModule get() = Shared.ViewModule
    val Vignette get() = Shared.Vignette
    val ViewQuilt get() = Shared.ViewQuilt
    val VolumeDownAlt get() = Shared.VolumeDownAlt
    val VolumeDown get() = Shared.VolumeDown
    val VolumeMute get() = Shared.VolumeMute
    val VolumeOff get() = Shared.VolumeOff
    val VolumeUp get() = Shared.VolumeUp
    val RingVolume get() = Shared.RingVolume
    val Warning get() = Shared.Warning
    val West get() = Shared.West
    val WbSunny get() = Shared.WbSunny
    val ZoomIn get() = Shared.ZoomIn
    val ZoomOutMap get() = Shared.ZoomOutMap
  }
  object Alternatives {
    val AdvancedSettings get() = Shared.Code
  }

  /** Material Symbols for Android platform APIs that require drawable resource IDs. */
  object Platform {
    @DrawableRes val FastRewind = MaterialSymbolsR.drawable.materialsymbols_ic_fast_rewind_rounded_filled
    @DrawableRes val FastForward = MaterialSymbolsR.drawable.materialsymbols_ic_fast_forward_rounded_filled
    @DrawableRes val Previous = MaterialSymbolsR.drawable.materialsymbols_ic_skip_previous_rounded_filled
    @DrawableRes val Play = MaterialSymbolsR.drawable.materialsymbols_ic_play_arrow_rounded_filled
    @DrawableRes val Pause = MaterialSymbolsR.drawable.materialsymbols_ic_pause_rounded_filled
    @DrawableRes val Next = MaterialSymbolsR.drawable.materialsymbols_ic_skip_next_rounded_filled
  }
}
