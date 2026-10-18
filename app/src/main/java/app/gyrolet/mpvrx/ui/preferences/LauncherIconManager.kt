/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.preferences

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.gyrolet.mpvrx.R

/** Backdrop of a launcher icon. Each one also needs a color in colors_app_icon.xml. */
internal enum class IconBackground(
  val key: String,
  @ColorRes val color: Int,
  @StringRes val label: Int,
) {
  Black("Black", R.color.icon_bg_black, R.string.app_icon_bg_black),
  White("White", R.color.icon_bg_white, R.string.app_icon_bg_white),
  Charcoal("Charcoal", R.color.icon_bg_charcoal, R.string.app_icon_bg_charcoal),
  Indigo("Indigo", R.color.icon_bg_indigo, R.string.app_icon_bg_indigo),
  Violet("Violet", R.color.icon_bg_violet, R.string.app_icon_bg_violet),
  Lagoon("Lagoon", R.color.icon_bg_lagoon, R.string.app_icon_bg_lagoon),
  Azure("Azure", R.color.icon_bg_azure, R.string.app_icon_bg_azure),
  Lilac("Lilac", R.color.icon_bg_lilac, R.string.app_icon_bg_lilac),
  Mint("Mint", R.color.icon_bg_mint, R.string.app_icon_bg_mint),
  Ember("Ember", R.color.icon_bg_ember, R.string.app_icon_bg_ember),
  Crimson("Crimson", R.color.icon_bg_crimson, R.string.app_icon_bg_crimson),
  Rose("Rose", R.color.icon_bg_rose, R.string.app_icon_bg_rose),
  Peach("Peach", R.color.icon_bg_peach, R.string.app_icon_bg_peach),
  Amber("Amber", R.color.icon_bg_amber, R.string.app_icon_bg_amber),
  Lemon("Lemon", R.color.icon_bg_lemon, R.string.app_icon_bg_lemon),
  Lime("Lime", R.color.icon_bg_lime, R.string.app_icon_bg_lime),
  Forest("Forest", R.color.icon_bg_forest, R.string.app_icon_bg_forest),
  Teal("Teal", R.color.icon_bg_teal, R.string.app_icon_bg_teal),
  Sky("Sky", R.color.icon_bg_sky, R.string.app_icon_bg_sky),
  Cobalt("Cobalt", R.color.icon_bg_cobalt, R.string.app_icon_bg_cobalt),
  Plum("Plum", R.color.icon_bg_plum, R.string.app_icon_bg_plum),
  Cocoa("Cocoa", R.color.icon_bg_cocoa, R.string.app_icon_bg_cocoa),
  Wallpaper("Wallpaper", R.color.icon_bg_wallpaper, R.string.app_icon_bg_wallpaper),
}

/** The ribbon drawn over a background, as a 108dp adaptive foreground layer. */
internal enum class IconMark(
  val key: String,
  @DrawableRes val layer: Int,
  @StringRes val label: Int,
) {
  Solid("Solid", R.drawable.ic_launcher_solid_purple_foreground, R.string.app_icon_logo_solid),
  Gradient("Gradient", R.drawable.ic_launcher_purple_foreground, R.string.app_icon_logo_gradient),
  White("White", R.drawable.ic_launcher_white_foreground, R.string.app_icon_logo_white),
  Black("Black", R.drawable.ic_launcher_black_foreground, R.string.app_icon_logo_black),
  Classic("Classic", R.drawable.ic_launcher_classic_foreground, R.string.app_icon_logo_classic),
}

/**
 * One launcher icon: a background with a mark over it. Only pairs that read well together exist, so
 * the catalog below is both the list of icons and the rule for which marks suit which backgrounds.
 * A new pair needs a row here, a mipmap, a splash style and an activity-alias named [aliasName] in the manifest.
 */
internal class LauncherIcon(
  val background: IconBackground,
  val mark: IconMark,
  @DrawableRes val preview: Int,
  @StyleRes val splashTheme: Int,
) {
  val aliasName: String get() = "app.gyrolet.mpvrx.Icon${background.key}${mark.key}"

  override fun equals(other: Any?) = other is LauncherIcon && other.background == background && other.mark == mark
  override fun hashCode() = background.hashCode() * 31 + mark.hashCode()

  companion object {
    val entries: List<LauncherIcon> = listOf(
      LauncherIcon(IconBackground.Black, IconMark.Solid, R.mipmap.ic_launcher_black_solid, R.style.Theme_mpvrx_Splash_BlackSolid),
      LauncherIcon(IconBackground.Black, IconMark.Gradient, R.mipmap.ic_launcher_black_gradient, R.style.Theme_mpvrx_Splash_BlackGradient),
      LauncherIcon(IconBackground.Black, IconMark.White, R.mipmap.ic_launcher_black_white, R.style.Theme_mpvrx_Splash_BlackWhite),
      LauncherIcon(IconBackground.White, IconMark.Solid, R.mipmap.ic_launcher_white_solid, R.style.Theme_mpvrx_Splash_WhiteSolid),
      LauncherIcon(IconBackground.White, IconMark.Gradient, R.mipmap.ic_launcher_white_gradient, R.style.Theme_mpvrx_Splash_WhiteGradient),
      LauncherIcon(IconBackground.White, IconMark.Black, R.mipmap.ic_launcher_white_black, R.style.Theme_mpvrx_Splash_WhiteBlack),
      LauncherIcon(IconBackground.Charcoal, IconMark.Solid, R.mipmap.ic_launcher_charcoal_solid, R.style.Theme_mpvrx_Splash_CharcoalSolid),
      LauncherIcon(IconBackground.Charcoal, IconMark.Gradient, R.mipmap.ic_launcher_charcoal_gradient, R.style.Theme_mpvrx_Splash_CharcoalGradient),
      LauncherIcon(IconBackground.Charcoal, IconMark.White, R.mipmap.ic_launcher_charcoal_white, R.style.Theme_mpvrx_Splash_CharcoalWhite),
      LauncherIcon(IconBackground.Indigo, IconMark.Solid, R.mipmap.ic_launcher_indigo_solid, R.style.Theme_mpvrx_Splash_IndigoSolid),
      LauncherIcon(IconBackground.Indigo, IconMark.White, R.mipmap.ic_launcher_indigo_white, R.style.Theme_mpvrx_Splash_IndigoWhite),
      LauncherIcon(IconBackground.Violet, IconMark.Solid, R.mipmap.ic_launcher_violet_solid, R.style.Theme_mpvrx_Splash_VioletSolid),
      LauncherIcon(IconBackground.Violet, IconMark.White, R.mipmap.ic_launcher_violet_white, R.style.Theme_mpvrx_Splash_VioletWhite),
      LauncherIcon(IconBackground.Lagoon, IconMark.White, R.mipmap.ic_launcher_lagoon_white, R.style.Theme_mpvrx_Splash_LagoonWhite),
      LauncherIcon(IconBackground.Lagoon, IconMark.Black, R.mipmap.ic_launcher_lagoon_black, R.style.Theme_mpvrx_Splash_LagoonBlack),
      LauncherIcon(IconBackground.Azure, IconMark.Solid, R.mipmap.ic_launcher_azure_solid, R.style.Theme_mpvrx_Splash_AzureSolid),
      LauncherIcon(IconBackground.Azure, IconMark.Gradient, R.mipmap.ic_launcher_azure_gradient, R.style.Theme_mpvrx_Splash_AzureGradient),
      LauncherIcon(IconBackground.Azure, IconMark.Black, R.mipmap.ic_launcher_azure_black, R.style.Theme_mpvrx_Splash_AzureBlack),
      LauncherIcon(IconBackground.Lilac, IconMark.Solid, R.mipmap.ic_launcher_lilac_solid, R.style.Theme_mpvrx_Splash_LilacSolid),
      LauncherIcon(IconBackground.Lilac, IconMark.Gradient, R.mipmap.ic_launcher_lilac_gradient, R.style.Theme_mpvrx_Splash_LilacGradient),
      LauncherIcon(IconBackground.Lilac, IconMark.Black, R.mipmap.ic_launcher_lilac_black, R.style.Theme_mpvrx_Splash_LilacBlack),
      LauncherIcon(IconBackground.Mint, IconMark.Solid, R.mipmap.ic_launcher_mint_solid, R.style.Theme_mpvrx_Splash_MintSolid),
      LauncherIcon(IconBackground.Mint, IconMark.Gradient, R.mipmap.ic_launcher_mint_gradient, R.style.Theme_mpvrx_Splash_MintGradient),
      LauncherIcon(IconBackground.Mint, IconMark.Black, R.mipmap.ic_launcher_mint_black, R.style.Theme_mpvrx_Splash_MintBlack),
      LauncherIcon(IconBackground.Ember, IconMark.White, R.mipmap.ic_launcher_ember_white, R.style.Theme_mpvrx_Splash_EmberWhite),
      LauncherIcon(IconBackground.Ember, IconMark.Black, R.mipmap.ic_launcher_ember_black, R.style.Theme_mpvrx_Splash_EmberBlack),
      LauncherIcon(IconBackground.Black, IconMark.Classic, R.mipmap.ic_launcher_black_classic, R.style.Theme_mpvrx_Splash_BlackClassic),
      LauncherIcon(IconBackground.White, IconMark.Classic, R.mipmap.ic_launcher_white_classic, R.style.Theme_mpvrx_Splash_WhiteClassic),
      LauncherIcon(IconBackground.Charcoal, IconMark.Classic, R.mipmap.ic_launcher_charcoal_classic, R.style.Theme_mpvrx_Splash_CharcoalClassic),
      LauncherIcon(IconBackground.Indigo, IconMark.Classic, R.mipmap.ic_launcher_indigo_classic, R.style.Theme_mpvrx_Splash_IndigoClassic),
      LauncherIcon(IconBackground.Violet, IconMark.Classic, R.mipmap.ic_launcher_violet_classic, R.style.Theme_mpvrx_Splash_VioletClassic),
      LauncherIcon(IconBackground.Azure, IconMark.Classic, R.mipmap.ic_launcher_azure_classic, R.style.Theme_mpvrx_Splash_AzureClassic),
      LauncherIcon(IconBackground.Lilac, IconMark.Classic, R.mipmap.ic_launcher_lilac_classic, R.style.Theme_mpvrx_Splash_LilacClassic),
      LauncherIcon(IconBackground.Mint, IconMark.Classic, R.mipmap.ic_launcher_mint_classic, R.style.Theme_mpvrx_Splash_MintClassic),
      LauncherIcon(IconBackground.Crimson, IconMark.White, R.mipmap.ic_launcher_crimson_white, R.style.Theme_mpvrx_Splash_CrimsonWhite),
      LauncherIcon(IconBackground.Crimson, IconMark.Black, R.mipmap.ic_launcher_crimson_black, R.style.Theme_mpvrx_Splash_CrimsonBlack),
      LauncherIcon(IconBackground.Rose, IconMark.White, R.mipmap.ic_launcher_rose_white, R.style.Theme_mpvrx_Splash_RoseWhite),
      LauncherIcon(IconBackground.Rose, IconMark.Black, R.mipmap.ic_launcher_rose_black, R.style.Theme_mpvrx_Splash_RoseBlack),
      LauncherIcon(IconBackground.Peach, IconMark.White, R.mipmap.ic_launcher_peach_white, R.style.Theme_mpvrx_Splash_PeachWhite),
      LauncherIcon(IconBackground.Peach, IconMark.Black, R.mipmap.ic_launcher_peach_black, R.style.Theme_mpvrx_Splash_PeachBlack),
      LauncherIcon(IconBackground.Amber, IconMark.White, R.mipmap.ic_launcher_amber_white, R.style.Theme_mpvrx_Splash_AmberWhite),
      LauncherIcon(IconBackground.Amber, IconMark.Black, R.mipmap.ic_launcher_amber_black, R.style.Theme_mpvrx_Splash_AmberBlack),
      LauncherIcon(IconBackground.Lemon, IconMark.White, R.mipmap.ic_launcher_lemon_white, R.style.Theme_mpvrx_Splash_LemonWhite),
      LauncherIcon(IconBackground.Lemon, IconMark.Black, R.mipmap.ic_launcher_lemon_black, R.style.Theme_mpvrx_Splash_LemonBlack),
      LauncherIcon(IconBackground.Lime, IconMark.White, R.mipmap.ic_launcher_lime_white, R.style.Theme_mpvrx_Splash_LimeWhite),
      LauncherIcon(IconBackground.Lime, IconMark.Black, R.mipmap.ic_launcher_lime_black, R.style.Theme_mpvrx_Splash_LimeBlack),
      LauncherIcon(IconBackground.Forest, IconMark.White, R.mipmap.ic_launcher_forest_white, R.style.Theme_mpvrx_Splash_ForestWhite),
      LauncherIcon(IconBackground.Forest, IconMark.Black, R.mipmap.ic_launcher_forest_black, R.style.Theme_mpvrx_Splash_ForestBlack),
      LauncherIcon(IconBackground.Teal, IconMark.White, R.mipmap.ic_launcher_teal_white, R.style.Theme_mpvrx_Splash_TealWhite),
      LauncherIcon(IconBackground.Teal, IconMark.Black, R.mipmap.ic_launcher_teal_black, R.style.Theme_mpvrx_Splash_TealBlack),
      LauncherIcon(IconBackground.Sky, IconMark.White, R.mipmap.ic_launcher_sky_white, R.style.Theme_mpvrx_Splash_SkyWhite),
      LauncherIcon(IconBackground.Sky, IconMark.Black, R.mipmap.ic_launcher_sky_black, R.style.Theme_mpvrx_Splash_SkyBlack),
      LauncherIcon(IconBackground.Cobalt, IconMark.White, R.mipmap.ic_launcher_cobalt_white, R.style.Theme_mpvrx_Splash_CobaltWhite),
      LauncherIcon(IconBackground.Cobalt, IconMark.Black, R.mipmap.ic_launcher_cobalt_black, R.style.Theme_mpvrx_Splash_CobaltBlack),
      LauncherIcon(IconBackground.Plum, IconMark.White, R.mipmap.ic_launcher_plum_white, R.style.Theme_mpvrx_Splash_PlumWhite),
      LauncherIcon(IconBackground.Plum, IconMark.Black, R.mipmap.ic_launcher_plum_black, R.style.Theme_mpvrx_Splash_PlumBlack),
      LauncherIcon(IconBackground.Cocoa, IconMark.White, R.mipmap.ic_launcher_cocoa_white, R.style.Theme_mpvrx_Splash_CocoaWhite),
      LauncherIcon(IconBackground.Cocoa, IconMark.Black, R.mipmap.ic_launcher_cocoa_black, R.style.Theme_mpvrx_Splash_CocoaBlack),
      LauncherIcon(IconBackground.Wallpaper, IconMark.White, R.mipmap.ic_launcher_wallpaper_white, R.style.Theme_mpvrx_Splash_WallpaperWhite),
      LauncherIcon(IconBackground.Wallpaper, IconMark.Black, R.mipmap.ic_launcher_wallpaper_black, R.style.Theme_mpvrx_Splash_WallpaperBlack),
    )

    /** Black with the solid purple ribbon: what the app has always shipped with. */
    val Default: LauncherIcon = of(IconBackground.Black, IconMark.Solid)!!

    fun of(background: IconBackground, mark: IconMark): LauncherIcon? =
      entries.firstOrNull { it.background == background && it.mark == mark }

    /** Marks that read on [background], in catalog order. */
    fun marksFor(background: IconBackground): List<IconMark> =
      entries.filter { it.background == background }.map { it.mark }

    /** The icon for [background] keeping [mark] when it reads there, else the background's own mark. */
    fun resolve(background: IconBackground, mark: IconMark): LauncherIcon =
      of(background, mark) ?: entries.first { it.background == background }

    /** Match free-form picks to a real packaged launcher icon, not a home-screen shortcut. */
    fun closest(context: Context, color: Int, mark: IconMark): LauncherIcon {
      val requested = DoubleArray(3)
      ColorUtils.colorToLAB(color or 0xFF000000.toInt(), requested)
      val candidate = DoubleArray(3)
      val background = IconBackground.entries.asSequence()
        .filter { it != IconBackground.Wallpaper }
        .minByOrNull { option ->
          ColorUtils.colorToLAB(ContextCompat.getColor(context, option.color), candidate)
          val dl = requested[0] - candidate[0]
          val da = requested[1] - candidate[1]
          val db = requested[2] - candidate[2]
          dl * dl + da * da + db * db
        } ?: IconBackground.Black
      return of(background, mark) ?: run {
        val bgColor = ContextCompat.getColor(context, background.color)
        val readable = if (ColorUtils.calculateLuminance(bgColor) > 0.25) IconMark.Black else IconMark.White
        of(background, readable) ?: resolve(background, mark)
      }
    }
  }
}

/**
 * The fixed icons from before the builder. They stay declared in the manifest, off, only so a
 * user's old choice can be read once and moved to the builder icon that looks the same.
 */
private enum class LegacyIcon(val phoneAlias: String, val tvAlias: String, val becomes: LauncherIcon) {
  SolidPurple(
    "app.gyrolet.mpvrx.SolidPurpleLauncher", "app.gyrolet.mpvrx.TvSolidPurpleLauncher", LauncherIcon.Default,
  ),
  Purple(
    "app.gyrolet.mpvrx.PurpleLauncher", "app.gyrolet.mpvrx.TvPurpleLauncher",
    LauncherIcon.of(IconBackground.Black, IconMark.Gradient)!!,
  ),
  Classic(
    "app.gyrolet.mpvrx.ClassicLauncher", "app.gyrolet.mpvrx.TvClassicLauncher",
    LauncherIcon.of(IconBackground.Black, IconMark.Classic)!!,
  ),
}

internal object LauncherIconManager {
  private const val ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
  private const val DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
  private const val DEFAULT_STATE = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

  @Volatile private var cachedCurrent: LauncherIcon? = null
  private var cachedArtwork: Pair<LauncherIcon, Bitmap>? = null

  private fun component(context: Context, name: String) = ComponentName(context.packageName, name)

  private fun stateOf(context: Context, name: String) =
    context.packageManager.getComponentEnabledSetting(component(context, name))

  /** What the package manager says is shown. An explicit pick beats the manifest default. */
  private fun readCurrent(context: Context): LauncherIcon {
    val explicit = LauncherIcon.entries.firstOrNull { stateOf(context, it.aliasName) == ENABLED }
    if (explicit != null) return explicit
    val legacy = LegacyIcon.entries.firstOrNull {
      stateOf(context, it.phoneAlias) == ENABLED || stateOf(context, it.tvAlias) == ENABLED
    }
    return legacy?.becomes ?: LauncherIcon.Default
  }

  fun current(context: Context): LauncherIcon =
    cachedCurrent ?: readCurrent(context).also { cachedCurrent = it }

  /**
   * Makes exactly one icon show: carries an old-style choice over to its builder icon, and repairs
   * a leftover state with none or several on. Does nothing when everything already agrees.
   */
  @Synchronized
  fun reconcile(context: Context) {
    val choice = readCurrent(context)
    val on = LauncherIcon.entries.filter {
      val state = stateOf(context, it.aliasName)
      state == ENABLED || (state == DEFAULT_STATE && it == LauncherIcon.Default)
    }
    val legacyOn = LegacyIcon.entries.any {
      stateOf(context, it.phoneAlias) == ENABLED || stateOf(context, it.tvAlias) == ENABLED
    }
    if (!legacyOn && on.size == 1 && on.first() == choice) {
      cachedCurrent = choice
      return
    }
    select(context, choice)
  }

  /**
   * Android 13+ keeps the splash theme chosen here for the next cold start, so the system splash
   * matches the launcher icon. Older versions cannot switch it and keep the default splash.
   */
  private fun applySplashTheme(activity: Activity, icon: LauncherIcon) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    runCatching { activity.splashScreen.setSplashScreenTheme(icon.splashTheme) }
  }

  /** Recents artwork failures must not turn a successful launcher change into an error. */
  @Suppress("DEPRECATION")
  fun refreshTaskIcon(activity: Activity) {
    runCatching { applySplashTheme(activity, current(activity)) }
    runCatching {
      val icon = current(activity)
      val customMode = CustomIconShortcut.appliedMode(activity)
      val bitmap = if (customMode == "color") {
        CustomIconShortcut.render(activity, CustomIconShortcut.savedColor(activity),
          CustomIconShortcut.savedMark(activity, icon.mark), size = 128,
          colorEnd = CustomIconShortcut.savedColorEnd(activity),
          angle = CustomIconShortcut.savedAngle(activity)) ?: return@runCatching
      } else {
        cachedArtwork?.takeIf { it.first == icon && icon.background != IconBackground.Wallpaper }?.second ?: run {
          val drawable: Drawable = ContextCompat.getDrawable(activity, icon.preview) ?: return@runCatching
          Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, it.width, it.height)
            drawable.draw(Canvas(it))
            cachedArtwork = icon to it
          }
        }
      }
      activity.setTaskDescription(ActivityManager.TaskDescription(activity.getString(R.string.app_name), bitmap))
    }
  }

  private fun findActivity(context: Context): Activity? {
    var current = context
    while (current is ContextWrapper) {
      if (current is Activity) return current
      val base = current.baseContext
      if (base === current) break
      current = base
    }
    return current as? Activity
  }

  /** Every alias this manager owns, mapped to the state it should end up in for [icon]. */
  private fun targetStates(context: Context, icon: LauncherIcon): Map<ComponentName, Int> {
    val names = LauncherIcon.entries.map { it.aliasName } +
      LegacyIcon.entries.flatMap { listOf(it.phoneAlias, it.tvAlias) }
    return names.associate { component(context, it) to (if (it == icon.aliasName) ENABLED else DISABLED) }
  }

  private fun applyStates(context: Context, changes: Map<ComponentName, Int>) {
    val pm = context.packageManager
    val flags = PackageManager.DONT_KILL_APP
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      pm.setComponentEnabledSettings(changes.map { (name, state) ->
        PackageManager.ComponentEnabledSetting(name, state, flags)
      })
    } else {
      // Turn the new one on before the rest go off, so the app never has no launcher entry.
      changes.filter { it.value == ENABLED }.forEach { (name, state) -> pm.setComponentEnabledSetting(name, state, flags) }
      changes.filter { it.value != ENABLED }.forEach { (name, state) -> pm.setComponentEnabledSetting(name, state, flags) }
    }
  }

  @Synchronized
  @Suppress("DEPRECATION")
  fun select(context: Context, icon: LauncherIcon) {
    // Fail before touching anything if this build lacks the alias.
    context.packageManager.getActivityInfo(component(context, icon.aliasName), PackageManager.MATCH_DISABLED_COMPONENTS)
    val changes = targetStates(context, icon)
    val previous = changes.keys.associateWith { context.packageManager.getComponentEnabledSetting(it) }
    try {
      applyStates(context, changes)
      check(changes.keys.all { context.packageManager.getComponentEnabledSetting(it) == changes.getValue(it) }) {
        "Launcher alias changes were not applied"
      }
    } catch (failure: Exception) {
      runCatching { applyStates(context, previous) }.exceptionOrNull()?.let(failure::addSuppressed)
      cachedCurrent = null
      throw failure
    }
    cachedCurrent = icon
    findActivity(context)?.let(::refreshTaskIcon)
  }
}
