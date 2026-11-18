/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * mpv.io/manual/master is generated from mpv-player/mpv's reStructuredText manual sources.
 * Read these authoritative plain-text sources rather than brittle HTML selectors.
 * The bundled snapshots keep the screen useful without network connectivity.
 */
package app.gyrolet.mpvrx.ui.editor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class MpvDocumentationState(
  val entries: List<HelpEntry> = emptyList(),
  val lastCheckedMillis: Long = 0L,
  val isBundledSnapshot: Boolean = true,
  val isRefreshing: Boolean = false,
  val error: String? = null,
)

@Serializable
private data class PersistedMpvManual(
  val schema: Int = 1,
  val lastCheckedMillis: Long,
  val entries: List<HelpEntry>,
)

/**
 * One process-wide catalog, loaded on Help/editor entry (never on player startup).
 * First install reads the official APK snapshot immediately and fetches current upstream docs.
 * Stale data is refreshed every ten days, failures preserve the last known-good catalog.
 */
object MpvDocumentationRepository {
  private const val TAG = "MpvDocumentation"
  private const val CACHE_SCHEMA = 1
  private const val MAX_SOURCE_CHARS = 1_500_000
  private const val REFRESH_AFTER_MS = 10L * 24 * 60 * 60 * 1000
  private const val RETRY_AFTER_FAILURE_MS = 6L * 60 * 60 * 1000
  private const val CACHE_FILE = "mpv-manual-v1.json"

  private val sourceFiles = listOf("options", "input", "javascript", "lua")
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val busy = AtomicBoolean(false)
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(55, TimeUnit.SECONDS)
    .build()

  private val _state = MutableStateFlow(MpvDocumentationState())
  val state = _state.asStateFlow()
  private var lastFailedAttemptMillis = 0L

  fun ensureLoaded(context: Context, forceRefresh: Boolean = false) {
    if (!busy.compareAndSet(false, true)) return
    val appContext = context.applicationContext
    scope.launch {
      try {
        if (_state.value.entries.isEmpty()) {
          val cached = readCache(appContext)
          if (cached != null) {
            _state.value = MpvDocumentationState(
              entries = cached.entries,
              lastCheckedMillis = cached.lastCheckedMillis,
              isBundledSnapshot = false,
            )
          } else {
            val bundled = parseAll(sourceFiles.associateWith { name ->
              appContext.assets.open("mpv_manual/$name.rst")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            })
            if (isValidCatalog(bundled)) {
              _state.value = MpvDocumentationState(entries = bundled)
            } else {
              Log.e(TAG, "Bundled upstream manual failed validation")
            }
          }
        }

        val now = System.currentTimeMillis()
        val checked = _state.value.lastCheckedMillis
        val due = forceRefresh || checked == 0L || now - checked >= REFRESH_AFTER_MS
        if (!due || (!forceRefresh && now - lastFailedAttemptMillis < RETRY_AFTER_FAILURE_MS)) return@launch

        _state.value = _state.value.copy(isRefreshing = true, error = null)
        try {
          val sources = sourceFiles.associateWith(::downloadOfficialSource)
          val parsed = parseAll(sources)
          require(isValidCatalog(parsed)) { "Invalid or incomplete mpv manual response" }
          val fetchedAt = System.currentTimeMillis()
          writeCache(appContext, PersistedMpvManual(
            schema = CACHE_SCHEMA,
            lastCheckedMillis = fetchedAt,
            entries = parsed,
          ))
          lastFailedAttemptMillis = 0L
          _state.value = MpvDocumentationState(
            entries = parsed,
            lastCheckedMillis = fetchedAt,
            isBundledSnapshot = false,
          )
        } catch (error: Exception) {
          lastFailedAttemptMillis = System.currentTimeMillis()
          Log.w(TAG, "Official mpv manual refresh failed; retaining cached docs", error)
          _state.value = _state.value.copy(
            error = "Refresh unavailable; cached documentation is still usable",
          )
        }
      } catch (error: Exception) {
        Log.e(TAG, "Unable to load offline mpv documentation", error)
        _state.value = _state.value.copy(error = "Could not load MPV documentation")
      } finally {
        _state.value = _state.value.copy(isRefreshing = false)
        busy.set(false)
      }
    }
  }

  private fun downloadOfficialSource(name: String): String {
    val request = Request.Builder()
      .url("https://raw.githubusercontent.com/mpv-player/mpv/master/DOCS/man/$name.rst")
      .header("Accept", "text/plain")
      .build()
    client.newCall(request).execute().use { response ->
      check(response.isSuccessful) { "mpv $name: HTTP ${response.code}" }
      val body = requireNotNull(response.body) { "Empty mpv $name response" }
      check(body.contentLength() <= MAX_SOURCE_CHARS) { "Oversize $name document" }
      val content = body.string()
      check(content.length in 1_000..MAX_SOURCE_CHARS &&
        !content.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)
      ) { "Invalid mpv $name source" }
      return content
    }
  }

  private fun readCache(context: Context): PersistedMpvManual? = runCatching {
    val file = File(context.filesDir, CACHE_FILE).takeIf { it.isFile } ?: return@runCatching null
    json.decodeFromString<PersistedMpvManual>(file.readText())
      .takeIf { it.schema == CACHE_SCHEMA && isValidCatalog(it.entries) }
  }.getOrNull()

  private fun writeCache(context: Context, data: PersistedMpvManual) {
    val target = File(context.filesDir, CACHE_FILE)
    val temporary = File(context.filesDir, "$CACHE_FILE.tmp")
    try {
      temporary.writeText(json.encodeToString(data))
      check(temporary.renameTo(target)) { "Cannot publish cached mpv manual" }
    } finally {
      temporary.delete()
    }
  }

  private fun isValidCatalog(entries: List<HelpEntry>): Boolean {
    val counts = entries.groupingBy(HelpEntry::kind).eachCount()
    return (counts[HelpEntryKind.OPTION] ?: 0) >= 400 &&
      (counts[HelpEntryKind.COMMAND] ?: 0) >= 70 &&
      (counts[HelpEntryKind.PROPERTY] ?: 0) >= 100 &&
      (counts[HelpEntryKind.JS_API] ?: 0) >= 25
  }

  private fun parseAll(sources: Map<String, String>): List<HelpEntry> =
    buildList {
      addAll(MpvManualRstParser.parseOptions(requireNotNull(sources["options"])))
      addAll(MpvManualRstParser.parseInput(requireNotNull(sources["input"])))
      val lua = MpvManualRstParser.parseJavaScript(requireNotNull(sources["lua"]))
        .associateBy { it.name }
      addAll(MpvManualRstParser.parseJavaScript(requireNotNull(sources["javascript"])).map { entry ->
        val luaDescription = lua[entry.name]?.description
        if (entry.description == MpvManualRstParser.API_REFERENCE_DESCRIPTION && luaDescription != null &&
          luaDescription != MpvManualRstParser.API_REFERENCE_DESCRIPTION
        ) entry.copy(description = luaDescription) else entry
      })
    }.distinctBy { it.kind to it.name }
}

/**
 * Parse only standalone reStructuredText definition terms, not arbitrary code examples or
 * references. Documentation names, signatures, and descriptions are taken from upstream.
 */
internal object MpvManualRstParser {
  const val API_REFERENCE_DESCRIPTION =
    "Official mpv JavaScript API. See upstream JavaScript and Lua manuals for full semantics."
  private val term = Regex("""``([^`]+)``""")
  private val optionName = Regex("""^--([a-z][a-z0-9-]*)""")
  private val commandName = Regex("""^([a-z][a-z0-9-]*)(?:\s|$)""")
  private val propertyName = Regex("""^[a-z][a-z0-9_./-]*$""")
  private val jsName = Regex("""(?:mp(?:\.[a-zA-Z_][\w]*)+|setTimeout|setInterval|clearTimeout|clearInterval|print|dump|require|JSON\.(?:parse|stringify))""")

  fun parseOptions(source: String): List<HelpEntry> = parse(source, "options")
  fun parseInput(source: String): List<HelpEntry> = parse(source, "input")
  fun parseJavaScript(source: String): List<HelpEntry> = parse(source, "javascript")

  private fun parse(source: String, document: String): List<HelpEntry> {
    val lines = source.lines()
    val entries = mutableListOf<HelpEntry>()
    var mode: HelpEntryKind? = when (document) {
      "options" -> HelpEntryKind.OPTION
      "javascript" -> HelpEntryKind.JS_API
      else -> null
    }
    var section = if (document == "options") "General" else "JavaScript"
    var pending = emptyList<Pair<String, String>>()
    var pendingKind: HelpEntryKind? = null
    var pendingCategory = ""
    val description = StringBuilder()

    fun flush() {
      val kind = pendingKind ?: return
      val readable = readableDescription(description.toString())
      if (readable.isNotBlank() || kind == HelpEntryKind.JS_API) {
        val descriptionText = readable.ifBlank { API_REFERENCE_DESCRIPTION }
        pending.forEach { (name, signature) ->
          entries += HelpEntry(name, kind, pendingCategory, signature, descriptionText)
        }
      }
      pending = emptyList()
      pendingKind = null
      description.clear()
    }

    for (i in lines.indices) {
      val line = lines[i]
      val stripped = line.trim()
      val next = lines.getOrNull(i + 1).orEmpty()
      val heading = line.isNotBlank() && line == stripped &&
        next.length >= line.length && next.length >= 3 &&
        next.all { it == '-' || it == '~' } && next.toSet().size == 1
      if (heading) {
        flush()
        if (document == "input") {
          when (stripped) {
            "List of Input Commands" -> mode = HelpEntryKind.COMMAND
            "Property list" -> mode = HelpEntryKind.PROPERTY
            "Property Expansion" -> mode = null
            else -> if (next.startsWith("~") && mode == HelpEntryKind.COMMAND) section = stripped
          }
        } else {
          section = stripped
        }
        continue
      }
      val active = mode
      val indent = line.length - line.trimStart().length
      if (active != null && (indent == 0 || active == HelpEntryKind.PROPERTY && indent == 4) &&
        stripped.startsWith("``")
      ) {
        val tokens = term.findAll(stripped).map { it.groupValues[1] }.toList()
        val remaining = stripped.replace(term, "").replace(",", "")
          .replace("(RW)", "").replace("(LE)", "").trim()
        val followedByDescription = next.startsWith(" ".repeat(indent + 4))
        if (tokens.isNotEmpty() && remaining.isEmpty() &&
          (followedByDescription || active == HelpEntryKind.JS_API)
        ) {
          val candidates = tokens.mapNotNull { token ->
            when (active) {
              HelpEntryKind.OPTION -> optionName.find(token)?.groupValues?.get(1)?.let { it to token }
              HelpEntryKind.COMMAND -> commandName.find(token)?.groupValues?.get(1)?.let { it to token }
              HelpEntryKind.PROPERTY -> token.takeIf(propertyName::matches)?.let { it to token }
              HelpEntryKind.JS_API -> jsName.find(token)?.value?.let { it to token }
            }
          }
          if (candidates.isNotEmpty()) {
            flush()
            pending = candidates
            pendingKind = active
            pendingCategory = when (active) {
              HelpEntryKind.OPTION -> "mpv.conf — $section"
              HelpEntryKind.COMMAND -> "Input Commands — $section"
              HelpEntryKind.PROPERTY -> "Properties — Official property list"
              HelpEntryKind.JS_API -> "JavaScript API — $section"
            }
            continue
          }
        }
      }
      if (pendingKind != null) description.appendLine(line)
    }
    flush()
    return entries
  }

  private fun readableDescription(source: String): String {
    val result = StringBuilder()
    var blank = false
    for (raw in source.lines()) {
      val line = raw.trim()
      if (line.startsWith(".. ") || line == "::" || line.startsWith(":")) continue
      if (line.isBlank()) {
        if (!blank && result.isNotEmpty()) result.append('\n')
        blank = true
        continue
      }
      if (result.isNotEmpty() && !blank) result.append(' ')
      result.append(term.replace(line, "$1"))
      blank = false
      if (result.length >= 2_500) break
    }
    return result.toString().trim().take(2_500)
  }
}
