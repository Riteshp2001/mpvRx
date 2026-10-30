/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.preferences.preference.PreferenceKind
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.defaultSharedPreferences
import app.gyrolet.mpvrx.ui.theme.saveWallpaperCopy
import app.gyrolet.mpvrx.utils.media.findChildIgnoreCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.koin.core.context.GlobalContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsManager(
  private val context: Context,
  private val preferenceStore: PreferenceStore,
  private val database: MpvRxDatabase,
) {
  companion object {
    private const val TAG_ROOT = "mpvRxSettings"
    private const val TAG_PREFERENCES = "preferences"
    private const val TAG_PREFERENCE = "preference"
    private const val TAG_DATABASE = "database"
    private const val TAG_NETWORK_CONNECTIONS = "networkConnections"
    private const val TAG_NETWORK_CONNECTION = "networkConnection"

    private const val ATTR_KEY = "key"
    private const val ATTR_TYPE = "type"
    private const val ATTR_VALUE = "value"
    private const val ATTR_EXPORT_DATE = "exportDate"
    private const val ATTR_VERSION = "version"
    private const val ATTR_FORMAT_VERSION = "formatVersion"
    private const val ATTR_ENCODING = "encoding"
    private const val FORMAT_VERSION = 2
    private const val ENCODING_BASE64 = "base64"
    private const val ENCODING_JSON = "base64-json"

    private const val TYPE_STRING = "string"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_STRING_SET = "stringSet"
    private const val STRING_SET_SEPARATOR = "|||"
    private const val AUTO_BACKUP_STATE = "settings_auto_backup_state"
    private const val LAST_BACKUP_SIGNATURE = "last_signature"
    private const val LAST_BACKUP_TIME = "last_time"
    private const val AUTO_BACKUP_RETENTION = 7
    private val AUTO_BACKUP_FILE_REGEX = Regex("""mpvrx_settings_[0-9]{8}_[0-9]{6}(?: \([0-9]+\))?\.xml""")
    private val BACKUP_TIMESTAMP_REGEX = Regex("""mpvrx_settings_([0-9]{8}_[0-9]{6})""", RegexOption.IGNORE_CASE)
    private const val MAX_BACKUP_BYTES = 64L * 1024 * 1024
    private const val MAX_TEXT_LENGTH = 4 * 1024 * 1024
    private const val MAX_SET_SIZE = 10_000
    private const val MAX_REPORTED_ERRORS = 20
  }

  suspend fun exportSettings(outputUri: Uri): Result<ExportStats> =
    withContext(Dispatchers.IO) {
      var temporaryFile: File? = null
      try {
        val snapshot = File.createTempFile("settings_", ".xml", context.cacheDir).also { temporaryFile = it }
        val stats = snapshot.outputStream().use { writeSettingsToXml(it) }
        currentCoroutineContext().ensureActive()
        context.contentResolver.openOutputStream(outputUri, "wt")?.use { outputStream ->
          snapshot.inputStream().use { it.copyTo(outputStream) }
          Result.success(stats)
        } ?: Result.failure(Exception("Failed to open output stream"))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Result.failure(e)
      } finally {
        temporaryFile?.delete()
      }
    }

  suspend fun importSettings(inputUri: Uri): Result<ImportStats> =
    withContext(Dispatchers.IO) {
      try {
        val size = DocumentFile.fromSingleUri(context, inputUri)?.length() ?: 0L
        require(size <= MAX_BACKUP_BYTES) { "Settings backup is too large" }
        context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
          val source = DocumentFile.fromSingleUri(context, inputUri)?.name ?: inputUri.lastPathSegment.orEmpty()
          val stats = readSettingsFromXml(inputStream, source)
          Result.success(stats)
        } ?: Result.failure(Exception("Failed to open input stream"))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Result.failure(e)
      } catch (e: OutOfMemoryError) {
        // A damaged or foreign file must never take the app down; report it like any bad backup.
        Result.failure(IllegalStateException("Settings backup is too large or damaged", e))
      } catch (e: StackOverflowError) {
        Result.failure(IllegalStateException("Settings backup is damaged", e))
      }
    }

  suspend fun autoBackupIfChanged(treeUri: String): Result<AutoBackupStats?> =
    withContext(Dispatchers.IO) {
      try {
        val configurationRoot = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
          ?: error("Backup folder is unavailable")
        val root = configurationRoot.findChildIgnoreCase("Backup")?.also { existing ->
          require(existing.isDirectory) { "Backup exists but is not a folder" }
        } ?: configurationRoot.createDirectory("Backup")
          ?: error("Unable to create Backup folder")
        require(root.canWrite()) { "Backup folder is not writable" }

        val snapshot = File.createTempFile("auto_backup_", ".xml", context.cacheDir)
        try {
          val stats = snapshot.outputStream().use { output -> writeSettingsToXml(output) }
          currentCoroutineContext().ensureActive()
          val signature = snapshotSignature(snapshot)
          val state = context.getSharedPreferences(AUTO_BACKUP_STATE, Context.MODE_PRIVATE)
          if (signature == state.getString(LAST_BACKUP_SIGNATURE, null)) return@withContext Result.success(null)

          val temporary = root.createFile("text/xml", ".mpvrx-settings-${System.currentTimeMillis()}.tmp")
            ?: error("Unable to create backup")
          var finalized = false
          try {
            context.contentResolver.openOutputStream(temporary.uri, "wt")?.use { output ->
              snapshot.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open backup")
            currentCoroutineContext().ensureActive()

            val finalName = getDefaultExportFilename()
            check(temporary.renameTo(finalName)) { "Unable to finalize backup" }
            finalized = true
            root.listFiles()
              .filter { file -> file.isFile && file.name?.matches(AUTO_BACKUP_FILE_REGEX) == true }
              .sortedByDescending { file -> file.name.orEmpty() }
              .drop(AUTO_BACKUP_RETENTION)
              .forEach(DocumentFile::delete)
            state.edit()
              .putString(LAST_BACKUP_SIGNATURE, signature)
              .putLong(LAST_BACKUP_TIME, System.currentTimeMillis())
              .apply()
            Result.success(AutoBackupStats(finalName, stats.totalExported))
          } finally {
            if (!finalized) temporary.delete()
          }
        } finally {
          snapshot.delete()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  suspend fun restoreNewestFromConfiguration(treeUri: String): Result<AutoRestoreStats?> =
    withContext(Dispatchers.IO) {
      try {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
          ?: error("Configuration folder is unavailable")
        val backupFolder = root.listFiles().firstOrNull { file ->
          file.isDirectory && file.name.equals("Backup", ignoreCase = true)
        }
        val candidates = (root.listFiles().asSequence() + backupFolder?.listFiles().orEmpty().asSequence())
          .filter { file ->
            file.isFile && file.name?.let { name ->
              name.startsWith("mpvrx_settings_", ignoreCase = true) && name.endsWith(".xml", ignoreCase = true)
            } == true
          }
          .sortedWith(
            compareByDescending<DocumentFile>(::backupSortTime)
              .thenByDescending { file -> file.name.orEmpty() },
          )
          .toList()

        var lastFailure: Throwable? = null
        for (candidate in candidates) {
          currentCoroutineContext().ensureActive()
          val restored = importSettings(candidate.uri)
          val stats = restored.getOrNull()
          if (stats != null) return@withContext Result.success(AutoRestoreStats(candidate.name.orEmpty(), stats))
          lastFailure = restored.exceptionOrNull()
        }
        if (candidates.isNotEmpty() && lastFailure != null) {
          Result.failure(lastFailure)
        } else {
          Result.success(null)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Result.failure(error)
      }
    }

  fun lastAutoBackupTime(): Long =
    context.getSharedPreferences(AUTO_BACKUP_STATE, Context.MODE_PRIVATE).getLong(LAST_BACKUP_TIME, 0L)

  private fun backupSortTime(file: DocumentFile): Long {
    val fromName = BACKUP_TIMESTAMP_REGEX.find(file.name.orEmpty())?.groupValues?.getOrNull(1)?.let { timestamp ->
      runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).parse(timestamp)?.time }.getOrNull()
    } ?: 0L
    return fromName.takeIf { it > 0L } ?: file.lastModified()
  }

  private fun snapshotSignature(snapshot: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val normalized = snapshot.readText().replace(Regex("""\s+exportDate="[^"]*"""), "")
    digest.update(normalized.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
  }

  private suspend fun writeSettingsToXml(outputStream: OutputStream): ExportStats {
    val allPreferences: MutableMap<String, Any?> = preferenceStore.getAll().toMutableMap()
    val wallpaper = allPreferences[AppearancePreferences.CUSTOM_WALLPAPER_URI_KEY] as? String
    if (!wallpaper.isNullOrBlank()) {
      allPreferences[AppearancePreferences.CUSTOM_WALLPAPER_URI_KEY] = saveWallpaperCopy(context, wallpaper)
    }
    val serializer: XmlSerializer = Xml.newSerializer()
    serializer.setOutput(outputStream, "UTF-8")
    serializer.startDocument("UTF-8", true)
    serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)

    serializer.startTag(null, TAG_ROOT)
    serializer.attribute(null, ATTR_FORMAT_VERSION, FORMAT_VERSION.toString())
    serializer.attribute(
      null,
      ATTR_VERSION,
      BuildConfig.VERSION_NAME,
    )
    serializer.attribute(
      null,
      ATTR_EXPORT_DATE,
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    )

    var exportedCount = 0
    val exportedKeys = mutableListOf<String>()

    serializer.startTag(null, TAG_PREFERENCES)
    for ((key, value) in allPreferences.toSortedMap()) {
      currentCoroutineContext().ensureActive()
      if (key != BrowserPreferences.ONBOARDING_COMPLETED_KEY &&
        key != AdvancedPreferences.AUTO_BACKUP_ENABLED_KEY &&
        key != AdvancedPreferences.AUTO_BACKUP_FOLDER_URI_KEY &&
        key != AdvancedPreferences.MEASURE_ENABLED_KEY &&
        value != null
      ) {
        writePreference(serializer, key, value)
        exportedCount++
        exportedKeys.add("pref:$key")
      }
    }
    serializer.endTag(null, TAG_PREFERENCES)

    serializer.startTag(null, TAG_DATABASE)

    val networkConnections = database.networkConnectionDao().getAllConnectionsList()
    serializer.startTag(null, TAG_NETWORK_CONNECTIONS)
    networkConnections.forEach { connection ->
      writeNetworkConnection(serializer, connection)
      exportedCount++
      exportedKeys.add("network:${connection.name}")
    }
    serializer.endTag(null, TAG_NETWORK_CONNECTIONS)

    serializer.endTag(null, TAG_DATABASE)

    serializer.endTag(null, TAG_ROOT)
    serializer.endDocument()
    serializer.flush()

    return ExportStats(
      totalExported = exportedCount,
      exportedKeys = exportedKeys,
    )
  }

  private fun sanitizeXmlValue(value: String): String {
    if (value.all { it in ' '..'~' }) return value
    return buildString(value.length) {
      var index = 0
      while (index < value.length) {
        val codePoint = Character.codePointAt(value, index)
        if (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
          codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF
        ) {
          appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
      }
    }
  }

  private fun writePreference(
    serializer: XmlSerializer,
    key: String,
    value: Any,
  ) {
    serializer.startTag(null, TAG_PREFERENCE)
    serializer.attribute(null, ATTR_KEY, key)

    when (value) {
      is String -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_STRING)
        if (sanitizeXmlValue(value) == value) {
          serializer.attribute(null, ATTR_VALUE, value)
        } else {
          serializer.attribute(null, ATTR_ENCODING, ENCODING_BASE64)
          serializer.attribute(null, ATTR_VALUE, Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
      }
      is Int -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_INT)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Long -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_LONG)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Float -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_FLOAT)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Boolean -> {
        serializer.attribute(null, ATTR_TYPE, TYPE_BOOLEAN)
        serializer.attribute(null, ATTR_VALUE, value.toString())
      }
      is Set<*> -> {
        require(value.all { it is String }) { "Invalid string set: $key" }
        serializer.attribute(null, ATTR_TYPE, TYPE_STRING_SET)
        serializer.attribute(null, ATTR_ENCODING, ENCODING_JSON)
        serializer.attribute(
          null,
          ATTR_VALUE,
          Base64.encodeToString(
            JSONArray(value.map(Any?::toString).sorted()).toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
          ),
        )
      }
      else -> error("Unsupported preference type: $key")
    }

    serializer.endTag(null, TAG_PREFERENCE)
  }

  private fun writeNetworkConnection(
    serializer: XmlSerializer,
    connection: NetworkConnection,
  ) {
    serializer.startTag(null, TAG_NETWORK_CONNECTION)
    serializer.attribute(null, "id", connection.id.toString())
    serializer.attribute(null, "name", sanitizeXmlValue(connection.name))
    serializer.attribute(null, "protocol", connection.protocol.name)
    serializer.attribute(null, "host", sanitizeXmlValue(connection.host))
    serializer.attribute(null, "port", connection.port.toString())
    serializer.attribute(null, "username", sanitizeXmlValue(connection.username))
    // Passwords are Android Keystore-bound ciphertext at rest. Exporting that envelope would both
    // expose credential material and create an unusable backup on another installation. Deliberately
    // omit it; non-anonymous imports stay disconnected until the user enters a password again.
    serializer.attribute(null, "path", sanitizeXmlValue(connection.path))
    serializer.attribute(null, "isAnonymous", connection.isAnonymous.toString())
    serializer.attribute(null, "lastConnected", connection.lastConnected.toString())
    serializer.attribute(null, "autoConnect", connection.autoConnect.toString())
    serializer.attribute(null, "useHttps", connection.useHttps.toString())
    serializer.endTag(null, TAG_NETWORK_CONNECTION)
  }

  private suspend fun readSettingsFromXml(
    inputStream: InputStream,
    source: String,
  ): ImportStats {
    declareAllPreferences()
    val parser: XmlPullParser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    // Detects the encoding (and a byte-order mark) from the file itself.
    parser.setInput(inputStream, null)
    parser.nextTag()
    parser.require(XmlPullParser.START_TAG, null, TAG_ROOT)
    val formatVersion = parser.getAttributeValue(null, ATTR_FORMAT_VERSION)?.trim()?.toIntOrNull() ?: 1
    require(formatVersion >= 1) { "Unsupported settings format: $formatVersion" }

    val stats = ImportStats()
    val imported = LinkedHashMap<String, Any>()
    val parents = ArrayDeque<String>()
    val networkConnections = mutableListOf<NetworkConnection>()
    fun skip(message: String) {
      stats.failed++
      if (stats.errors.size < MAX_REPORTED_ERRORS) stats.errors.add(message)
    }

    var eventType = parser.eventType
    try {
      while (eventType != XmlPullParser.END_DOCUMENT) {
        currentCoroutineContext().ensureActive()
        when (eventType) {
          XmlPullParser.START_TAG -> {
            parents.addLast(parser.name)
            when (parser.name) {
              TAG_ROOT -> stats.version = parser.getAttributeValue(null, ATTR_VERSION) ?: "unknown"
              TAG_PREFERENCE -> {
                val key = parser.getAttributeValue(null, ATTR_KEY)
                if (parents.size == 3 && parents[1] == TAG_PREFERENCES &&
                  key != BrowserPreferences.ONBOARDING_COMPLETED_KEY &&
                  key != AdvancedPreferences.AUTO_BACKUP_ENABLED_KEY &&
                  key != AdvancedPreferences.AUTO_BACKUP_FOLDER_URI_KEY &&
                    key != AdvancedPreferences.MEASURE_ENABLED_KEY
                ) {
                  try {
                    val (validKey, value) = readPreference(parser)
                    imported[validKey] = value
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (e: Exception) {
                    skip("Skipped preference ${key.orEmpty()}: ${e.message}")
                  }
                }
              }
              TAG_NETWORK_CONNECTION -> {
                if (parents.size == 4 && parents[1] == TAG_DATABASE && parents[2] == TAG_NETWORK_CONNECTIONS) {
                  try {
                    networkConnections.add(readNetworkConnection(parser))
                  } catch (e: Exception) {
                    skip("Skipped network connection: ${e.message}")
                  }
                }
              }
            }
          }
          XmlPullParser.END_TAG -> parents.removeLastOrNull()
        }
        eventType = parser.next()
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (e: Exception) {
      // A truncated or damaged tail: keep every complete entry read before it.
      if (imported.isEmpty() && networkConnections.isEmpty()) throw e
      skip("Backup ends early: ${e.message}")
    }

    currentCoroutineContext().ensureActive()
    check(imported.isNotEmpty() || networkConnections.isNotEmpty() || stats.failed == 0) {
      stats.errors.joinToString("\n")
    }

    if (imported.isNotEmpty()) {
      val preferences = context.defaultSharedPreferences()
      val current = preferences.all
      val changes = imported.mapNotNull { (key, value) ->
        val previous = current[key]
        if (previous == value) null else SettingsRestoreGuard.Change(key, previous, value)
      }
      SettingsRestoreGuard.arm(context, source, changes)
      val editor = preferences.edit()
      changes.forEach { change -> putValue(editor, change.key, change.imported) }
      check(editor.commit()) { "Failed to save imported preferences" }
      stats.imported += imported.size
    }

    // Insert all database data
    try {
      if (networkConnections.isNotEmpty()) {
        database.networkConnectionDao().insertAll(networkConnections)
        stats.imported += networkConnections.size
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (e: Exception) {
      stats.failed += networkConnections.size
      stats.errors.add("Failed to insert database data: ${e.message}")
    }

    return stats
  }

  /** Every preference declares its kind when created; make sure all of them exist before validating. */
  private fun declareAllPreferences() {
    val koin = runCatching { GlobalContext.get() }.getOrNull() ?: return
    listOf<() -> Any>(
      { koin.get<AppearancePreferences>() },
      { koin.get<PlayerPreferences>() },
      { koin.get<GesturePreferences>() },
      { koin.get<DecoderPreferences>() },
      { koin.get<SubtitlesPreferences>() },
      { koin.get<AudioPreferences>() },
      { koin.get<AdvancedPreferences>() },
      { koin.get<BrowserPreferences>() },
      { koin.get<FoldersPreferences>() },
      { koin.get<NetworkBookmarkPreferences>() },
      { koin.get<AiPreferences>() },
      { koin.get<YtdlPreferences>() },
      { koin.get<SecureFolderPreferences>() },
      { koin.get<SeerrPreferences>() },
      { koin.get<MediaServerPreferences>() },
      { koin.get<DownloadPreferences>() },
    ).forEach { resolve -> runCatching { resolve() } }
  }

  @Suppress("UNCHECKED_CAST")
  private fun putValue(
    editor: SharedPreferences.Editor,
    key: String,
    value: Any,
  ) {
    when (value) {
      is String -> editor.putString(key, value)
      is Int -> editor.putInt(key, value)
      is Long -> editor.putLong(key, value)
      is Float -> editor.putFloat(key, value)
      is Boolean -> editor.putBoolean(key, value)
      is Set<*> -> editor.putStringSet(key, value as Set<String>)
    }
  }

  /** Reads one preference and returns it in the kind the app reads it as; throws when unusable. */
  private suspend fun readPreference(parser: XmlPullParser): Pair<String, Any> {
    val key = requireNotNull(parser.getAttributeValue(null, ATTR_KEY)?.takeIf(String::isNotBlank)) { "Missing preference key" }
    val type = requireNotNull(parser.getAttributeValue(null, ATTR_TYPE)) { "Missing preference type" }
    val valueStr = requireNotNull(parser.getAttributeValue(null, ATTR_VALUE)) { "Missing preference value" }
    val encoding = parser.getAttributeValue(null, ATTR_ENCODING)

    val parsed: Any =
      when (type) {
        TYPE_STRING ->
          when (encoding) {
            null -> valueStr
            ENCODING_BASE64 -> String(Base64.decode(valueStr, Base64.DEFAULT), Charsets.UTF_8)
            else -> error("Unsupported string encoding: $encoding")
          }
        TYPE_INT -> valueStr.trim().toInt()
        TYPE_LONG -> valueStr.trim().toLong()
        TYPE_FLOAT -> valueStr.trim().toFloat()
        TYPE_BOOLEAN -> valueStr.trim().lowercase(Locale.ROOT).toBooleanStrict()
        TYPE_STRING_SET ->
          when (encoding) {
            ENCODING_JSON -> {
              val values = JSONArray(String(Base64.decode(valueStr, Base64.DEFAULT), Charsets.UTF_8))
              (0 until values.length()).map { index ->
                val value = values.get(index)
                require(value is String) { "Invalid string set" }
                value
              }.toSet()
            }
            null -> if (valueStr.isEmpty()) emptySet() else valueStr.split(STRING_SET_SEPARATOR).toSet()
            else -> error("Unsupported string-set encoding: $encoding")
          }
        else -> error("Unsupported preference type: $type")
      }

    // A key the app reads differently than the backup stored it would otherwise be dropped at
    // read time, or worse, read by code that never expected that kind.
    val expected =
      preferenceStore.declaredKind(key) ?: when (preferenceStore.getAll()[key]) {
        is String -> PreferenceKind.STRING
        is Int -> PreferenceKind.INT
        is Long -> PreferenceKind.LONG
        is Float -> PreferenceKind.FLOAT
        is Boolean -> PreferenceKind.BOOLEAN
        is Set<*> -> PreferenceKind.STRING_SET
        else -> null
      }
    val value = if (expected == null) parsed else coerce(parsed, expected) ?: error("expected ${expected.name.lowercase()}")

    return key to when (value) {
      is String -> {
        if (key == AppearancePreferences.CUSTOM_WALLPAPER_URI_KEY) {
          if (value.isBlank()) value else saveWallpaperCopy(context, value)
        } else {
          require(value.length <= MAX_TEXT_LENGTH) { "Value is too large" }
          require(preferenceStore.acceptsStoredText(key, value)) { "Unreadable value" }
          value
        }
      }
      is Float -> value.also { require(it.isFinite()) { "Invalid number" } }
      is Set<*> -> value.also { set ->
        require(set.size <= MAX_SET_SIZE && set.all { it is String && it.length <= MAX_TEXT_LENGTH }) { "Invalid list" }
      }
      else -> value
    }
  }

  private fun coerce(
    value: Any,
    kind: PreferenceKind,
  ): Any? =
    when (kind) {
      PreferenceKind.STRING ->
        when (value) {
          is String -> value
          is Set<*> -> null
          else -> value.toString()
        }
      PreferenceKind.INT ->
        when (value) {
          is Int -> value
          is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
          is Float -> value.takeIf { it.isFinite() && it == kotlin.math.floor(it) && it in Int.MIN_VALUE.toFloat()..Int.MAX_VALUE.toFloat() }?.toInt()
          is String -> value.trim().toIntOrNull()
          else -> null
        }
      PreferenceKind.LONG ->
        when (value) {
          is Long -> value
          is Int -> value.toLong()
          is String -> value.trim().toLongOrNull()
          else -> null
        }
      PreferenceKind.FLOAT ->
        when (value) {
          is Float -> value
          is Int -> value.toFloat()
          is Long -> value.toFloat()
          is String -> value.trim().toFloatOrNull()
          else -> null
        }?.takeIf { it.isFinite() }
      PreferenceKind.BOOLEAN ->
        when (value) {
          is Boolean -> value
          is String -> value.trim().lowercase(Locale.ROOT).toBooleanStrictOrNull()
          else -> null
        }
      PreferenceKind.STRING_SET -> value as? Set<*>
    }

  private fun readNetworkConnection(parser: XmlPullParser): NetworkConnection =
    NetworkConnection(
      id = 0, // Will be auto-generated
      name = parser.getAttributeValue(null, "name") ?: "",
      protocol =
        NetworkProtocol.valueOf(
          parser.getAttributeValue(null, "protocol")?.trim() ?: "SMB",
        ),
      host = requireNotNull(parser.getAttributeValue(null, "host")?.takeIf(String::isNotBlank)) { "Missing host" },
      port = parser.getAttributeValue(null, "port")?.trim()?.toIntOrNull()?.takeIf { it in 0..65_535 } ?: 445,
      username = parser.getAttributeValue(null, "username") ?: "",
      // Never restore credentials from settings XML, including legacy exports that contained
      // plaintext passwords or device-bound encrypted envelopes.
      password = "",
      path = parser.getAttributeValue(null, "path") ?: "/",
      isAnonymous = parser.getAttributeValue(null, "isAnonymous")?.trim()?.toBooleanStrictOrNull() ?: false,
      lastConnected = parser.getAttributeValue(null, "lastConnected")?.trim()?.toLongOrNull() ?: 0L,
      // Avoid repeated authentication attempts before credentials have been re-entered.
      autoConnect = false,
      useHttps = parser.getAttributeValue(null, "useHttps")?.trim()?.toBooleanStrictOrNull() ?: false,
    )

  fun getDefaultExportFilename(): String {
    val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
    return "mpvrx_settings_${dateFormat.format(Date())}.xml"
  }

  data class ImportStats(
    var imported: Int = 0,
    var failed: Int = 0,
    var version: String = "unknown",
    val errors: MutableList<String> = mutableListOf(),
  )

  data class ExportStats(
    val totalExported: Int,
    val exportedKeys: List<String>,
  )

  data class AutoBackupStats(
    val fileName: String,
    val totalExported: Int,
  )

  data class AutoRestoreStats(
    val fileName: String,
    val importStats: ImportStats,
  )
}
