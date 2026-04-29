package app.gyrolet.mpvrx.ui.editor

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionHelper
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.subscribeAlways
import org.eclipse.tm4e.core.registry.IThemeSource

@Composable
fun MpvScriptEditor(
  content: String,
  onContentChange: (String) -> Unit,
  language: String,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val colors = androidx.compose.material3.MaterialTheme.colorScheme
  val selectionColors = LocalTextSelectionColors.current
  val isDarkTheme = isSystemInDarkTheme()
  val latestOnContentChange by rememberUpdatedState(onContentChange)
  var applyingExternalText by remember { mutableStateOf(false) }
  val textSize = 14.sp

  LaunchedEffect(Unit) {
    ScriptEditorTextMate.ensureInitialized(context)
  }

  val editor = remember {
    ScriptEditorTextMate.ensureInitialized(context)
    CodeEditor(context).apply {
      setTextSize(textSize.value)
      typefaceText = Typeface.MONOSPACE
      typefaceLineNumber = Typeface.MONOSPACE
      setPinLineNumber(true)
      editable = true
      colorScheme = createEditorColorScheme(isDarkTheme)
      colorScheme.applyMpvColors(
        colors = colors,
        selectionBackground = selectionColors.backgroundColor.toArgb(),
      )
      setEditorLanguage(language.toTextMateLanguage())
      subscribeAlways<ContentChangeEvent> { event ->
        if (!applyingExternalText) {
          latestOnContentChange(event.editor.text.toString())
        }
      }
    }
  }

  DisposableEffect(editor) {
    onDispose {
      editor.release()
    }
  }

  LaunchedEffect(isDarkTheme, colors, selectionColors) {
    editor.colorScheme = createEditorColorScheme(isDarkTheme)
    editor.colorScheme.applyMpvColors(
      colors = colors,
      selectionBackground = selectionColors.backgroundColor.toArgb(),
    )
  }

  LaunchedEffect(language) {
    editor.setEditorLanguage(language.toTextMateLanguage())
  }

  LaunchedEffect(content) {
    val editorText = editor.text.toString()
    if (editorText != content) {
      applyingExternalText = true
      editor.setText(content)
      applyingExternalText = false
    }
  }

  AndroidView(
    factory = {
      ScriptEditorTextMate.ensureInitialized(it)
      editor
    },
    update = {
      it.setTextSize(textSize.value)
    },
    modifier = modifier,
  )
}

private object ScriptEditorTextMate {
  @Volatile
  private var initialized = false

  fun ensureInitialized(context: Context) {
    if (initialized) return

    synchronized(this) {
      if (initialized) return

      runCatching {
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

        val themeRegistry = ThemeRegistry.getInstance()
        listOf("darcula", "quietlight").forEach { themeName ->
          val path = "textmate/$themeName.json"
          themeRegistry.loadTheme(
            ThemeModel(
              IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream(path),
                path,
                null,
              ),
              themeName,
            ),
          )
        }
        initialized = true
      }.onFailure { error ->
        Log.w("MpvScriptEditor", "TextMate assets failed to initialize", error)
      }
    }
  }

  fun setTheme(isDarkTheme: Boolean) {
    runCatching {
      ThemeRegistry.getInstance().setTheme(if (isDarkTheme) "darcula" else "quietlight")
    }
  }
}

private fun createEditorColorScheme(isDarkTheme: Boolean): EditorColorScheme {
  ScriptEditorTextMate.setTheme(isDarkTheme)
  return TextMateColorScheme.create(ThemeRegistry.getInstance())
}

private fun String.toTextMateLanguage(): Language {
  val normalizedLanguage = lowercase()
  val scopeName = when (normalizedLanguage) {
    "lua" -> "source.lua"
    "js", "javascript" -> "source.js"
    "mpv.conf", "mpv-conf", "mpv_config" -> "source.mpv.conf"
    "input.conf", "input-conf", "input_config" -> "source.mpv.input"
    else -> null
  }
  val completionMode = when (normalizedLanguage) {
    "lua", "js", "javascript" -> MpvCompletionMode.SCRIPT
    "mpv.conf", "mpv-conf", "mpv_config" -> MpvCompletionMode.MPV_CONF
    "input.conf", "input-conf", "input_config" -> MpvCompletionMode.INPUT_CONF
    else -> null
  }

  val baseLanguage = scopeName
    ?.let { 
      runCatching { 
        TextMateLanguage.create(it, false)
      }.getOrNull() 
    }
    ?: EmptyLanguage()

  return completionMode
    ?.let { MpvLanguageWrapper(baseLanguage, it) }
    ?: baseLanguage
}

private class MpvLanguageWrapper(
  private val base: Language,
  private val completionMode: MpvCompletionMode,
) : Language by base {
  override fun requireAutoComplete(
    content: ContentReference,
    position: CharPosition,
    publisher: CompletionPublisher,
    extraArguments: Bundle
  ) {
    base.requireAutoComplete(content, position, publisher, extraArguments)
    val prefix = CompletionHelper.computePrefix(content, position, ::isMpvCompletionChar)
    MpvAutoCompleteProvider.provideCompletion(prefix, completionMode, publisher)
  }
}

private fun isMpvCompletionChar(ch: Char): Boolean =
  ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == '/' || ch == '+'

private fun EditorColorScheme.applyMpvColors(
  colors: androidx.compose.material3.ColorScheme,
  selectionBackground: Int,
) {
  setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.background.toArgb())
  setColor(EditorColorScheme.TEXT_NORMAL, colors.onBackground.toArgb())
  setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.surfaceContainerLowest.toArgb())
  setColor(EditorColorScheme.LINE_NUMBER, colors.onSurfaceVariant.copy(alpha = 0.62f).toArgb())
  setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.primary.toArgb())
  setColor(EditorColorScheme.LINE_DIVIDER, colors.outlineVariant.copy(alpha = 0.55f).toArgb())
  setColor(EditorColorScheme.CURRENT_LINE, colors.surfaceContainerHighest.copy(alpha = 0.42f).toArgb())
  setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, selectionBackground)
  setColor(EditorColorScheme.SELECTION_INSERT, colors.primary.toArgb())
  setColor(EditorColorScheme.SELECTION_HANDLE, colors.primary.toArgb())
  setColor(EditorColorScheme.SCROLL_BAR_THUMB, colors.primary.copy(alpha = 0.42f).toArgb())
  setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, colors.primary.toArgb())
  setColor(EditorColorScheme.SCROLL_BAR_TRACK, colors.surfaceVariant.copy(alpha = 0.35f).toArgb())
  setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, colors.surfaceContainerHigh.toArgb())
  setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, colors.primaryContainer.toArgb())
  setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, colors.onSurface.toArgb())
  setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, colors.onSurfaceVariant.toArgb())
  setColor(EditorColorScheme.COMPLETION_WND_TEXT_MATCHED, colors.primary.toArgb())
}
