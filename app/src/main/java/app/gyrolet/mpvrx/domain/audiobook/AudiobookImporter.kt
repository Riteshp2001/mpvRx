package app.gyrolet.mpvrx.domain.audiobook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.dao.AudiobookDao
import app.gyrolet.mpvrx.database.entities.AudiobookChapterEntity
import app.gyrolet.mpvrx.database.entities.AudiobookEntity
import app.gyrolet.mpvrx.database.entities.AudiobookTrackEntity
import app.gyrolet.mpvrx.utils.media.openPersistedTreeDocument
import app.gyrolet.mpvrx.utils.sort.SortUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

internal class AudiobookImporter(
  private val context: Context,
  private val dao: AudiobookDao,
) {
  private data class Source(
    val document: DocumentFile,
    val path: String,
  )

  private data class Scanned(
    val source: Source,
    val track: AudiobookTrackEntity,
    val tags: Map<String, String>,
    val disc: Int,
    val number: Int,
  )

  suspend fun importBook(
    uris: List<Uri>,
    folder: Uri? = null,
    onProgress: (Int, Int) -> Unit,
  ): Long =
    withContext(Dispatchers.IO) {
      val root = folder?.let { openPersistedTreeDocument(context, it.toString()) ?: throw IOException(it.toString()) }
      AudiobookMarkerUtils.ensureMarker(context, folder, root)
      val sources =
        if (root != null) {
          collectFiles(root)
        } else {
          uris.distinct().map { uri ->
            val file = DocumentFile.fromSingleUri(context, uri) ?: throw IOException(uri.toString())
            AudiobookMarkerUtils.ensureMarker(context, uri, file)
            Source(file, file.name.orEmpty())
          }
        }
      val audio =
        sources.filter {
          it.document.name
            ?.substringAfterLast('.')
            ?.lowercase() in
            FileTypeUtils.AUDIO_EXTENSIONS
        }
      if (audio.isEmpty()) throw IOException(context.getString(R.string.audiobook_no_audio))
      val sourceKey = folder?.toString() ?: digest(audio.map { it.document.uri.toString() }.sorted().joinToString("\n"))
      dao.findBySource(sourceKey)?.let {
        AudiobookMarkerUtils.clearCache()
        app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
          .notifyChanged()
        return@withContext it
      }
      val metadata = readMetadata(sources)
      var coverUri =
        sources
          .firstOrNull {
            it.document.name?.lowercase() in setOf("cover.jpg", "cover.png", "folder.jpg", "folder.png")
          }?.document
          ?.uri
          ?.toString()
      val scanned =
        audio.mapIndexed { index, source ->
          currentCoroutineContext().ensureActive()
          onProgress(index + 1, audio.size)
          val retriever = MediaMetadataRetriever()
          try {
            retriever.setDataSource(context, source.document.uri)

            fun tag(key: Int): String = retriever.extractMetadata(key)?.trim().orEmpty()
            val duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0
            if (duration <= 0) throw IOException(context.getString(R.string.audiobook_unreadable, source.path))
            if (coverUri == null) coverUri = saveCover(retriever.embeddedPicture, sourceKey)
            Scanned(
              source,
              AudiobookTrackEntity(
                bookId = 0,
                uri = source.document.uri.toString(),
                fileName = source.document.name.orEmpty(),
                title =
                  tag(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank {
                    source.document.name
                      .orEmpty()
                      .substringBeforeLast('.')
                  },
                position = index,
                durationMs = duration,
                size = source.document.length(),
              ),
              mapOf(
                "title" to tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                "author" to
                  tag(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST,
                  ).ifBlank { tag(MediaMetadataRetriever.METADATA_KEY_AUTHOR) },
                "narrator" to tag(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                "genre" to tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
                "publishedYear" to tag(MediaMetadataRetriever.METADATA_KEY_YEAR),
              ),
              tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
              tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0,
            )
          } catch (failure: Exception) {
            throw IOException(context.getString(R.string.audiobook_unreadable, source.path), failure)
          } finally {
            runCatching { retriever.release() }
          }
        }
      val numbered = scanned.all { it.number > 0 }
      val ordered =
        scanned.sortedWith { first, second ->
          val disc = if (numbered) first.disc.compareTo(second.disc) else 0
          val track = if (numbered && disc == 0) first.number.compareTo(second.number) else 0
          when {
            disc != 0 -> disc
            track != 0 -> track
            else -> SortUtils.NaturalOrderComparator.DEFAULT.compare(first.source.path, second.source.path)
          }
        }

      fun field(key: String): String =
        metadata.text(key).ifBlank {
          ordered.firstNotNullOfOrNull { it.tags[key]?.takeIf(String::isNotBlank) }.orEmpty()
        }
      val seriesValue = metadata.optJSONArray("series")?.opt(0)
      val series = seriesValue as? JSONObject
      val parsedTitle = field("title").ifBlank { root?.name ?: ordered.first().track.title }
      val parsedAuthor = metadata.names("authors").ifBlank { field("author") }

      if (coverUri == null && parsedTitle.isNotBlank()) {
        runCatching {
          val onlineMatch = AudiobookCoverFetcher.search(parsedTitle, parsedAuthor).firstOrNull()
          if (onlineMatch != null) {
            coverUri = AudiobookCoverFetcher.downloadAndSaveCover(context, onlineMatch.coverUrl, sourceKey)
          }
        }
      }

      val book =
        AudiobookEntity(
          sourceKey = sourceKey,
          title = parsedTitle,
          subtitle = metadata.text("subtitle"),
          author = parsedAuthor,
          narrator = metadata.names("narrators").ifBlank { field("narrator") },
          series = series?.text("name") ?: (seriesValue as? String) ?: metadata.text("series"),
          seriesPart = series?.text("sequence") ?: metadata.text("seriesPart"),
          description = metadata.text("description"),
          genre = metadata.names("genres").ifBlank { field("genre") },
          language = metadata.text("language"),
          publisher = metadata.text("publisher"),
          publishedYear = field("publishedYear"),
          isbn = metadata.text("isbn"),
          asin = metadata.text("asin"),
          abridged =
            if (metadata.has("abridged") &&
              !metadata.isNull("abridged")
            ) {
              metadata.optBoolean("abridged")
            } else {
              null
            },
          coverUri = coverUri,
        )
      val id = dao.importBook(book, ordered.map { it.track })
      metadata.optJSONArray("chapters")?.let { chapterData ->
        var offset = 0L
        dao.getBook(id)?.orderedTracks?.forEach { track ->
          val chapters =
            (0 until chapterData.length()).mapNotNull { chapterIndex ->
              val chapter = chapterData.optJSONObject(chapterIndex) ?: return@mapNotNull null
              val start =
                (chapter.optDouble("start", Double.NaN) * 1000).takeIf(Double::isFinite)?.toLong()
                  ?: return@mapNotNull null
              val end =
                (chapter.optDouble("end", Double.NaN) * 1000).takeIf(Double::isFinite)?.toLong()
                  ?: return@mapNotNull null
              val localStart = (start - offset).coerceAtLeast(0)
              val localEnd = (end - offset).coerceAtMost(track.durationMs)
              if (localEnd <= localStart) {
                null
              } else {
                AudiobookChapterEntity(
                  track.id,
                  localStart,
                  localEnd,
                  chapter
                    .text(
                      "title",
                    ).ifBlank { context.getString(R.string.audiobook_chapter_number, chapterIndex + 1) },
                )
              }
            }
          if (chapters.isNotEmpty()) dao.replaceChapters(track.id, chapters)
          offset += track.durationMs
        }
      }
      AudiobookMarkerUtils.clearCache()
      app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
        .notifyChanged()
      id
    }

  private suspend fun collectFiles(root: DocumentFile): List<Source> {
    val result = mutableListOf<Source>()
    val pending = ArrayDeque<Source>().apply { add(Source(root, "")) }
    val visited = mutableSetOf<String>()
    while (pending.isNotEmpty()) {
      currentCoroutineContext().ensureActive()
      val parent = pending.removeFirst()
      if (!visited.add(parent.document.uri.toString())) continue
      if (!parent.document.canRead()) throw IOException(parent.path)
      parent.document.listFiles().forEach { child ->
        val entry = Source(child, "${parent.path}/${child.name.orEmpty()}")
        if (child.isDirectory) {
          pending.add(entry)
        } else if (child.isFile) {
          result.add(entry)
        }
      }
    }
    return result
  }

  private fun readMetadata(files: List<Source>): JSONObject {
    val source =
      files.firstOrNull { it.document.name.equals("metadata.json", true) }
        ?: files.firstOrNull { it.document.name?.endsWith(".opf", true) == true }
        ?: return JSONObject()
    return runCatching {
      val text =
        context.contentResolver.openInputStream(source.document.uri)?.bufferedReader()?.use { reader ->
          val buffer = CharArray(4096)
          buildString {
            while (true) {
              val count = reader.read(buffer)
              if (count < 0) break
              if (length + count > 1024 * 1024) throw IOException("Metadata is too large")
              append(buffer, 0, count)
            }
          }
        } ?: return JSONObject()
      if (source.document.name?.endsWith(".json", true) == true) {
        val json = JSONObject(text)
        json.optJSONObject("metadata") ?: json
      } else {
        readOpf(text)
      }
    }.getOrDefault(JSONObject())
  }

  private fun readOpf(text: String): JSONObject {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
      }
    val document = factory.newDocumentBuilder().parse(text.byteInputStream())

    fun elements(name: String): List<Element> {
      val nodes = document.getElementsByTagNameNS("*", name)
      return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }
    return JSONObject().apply {
      mapOf(
        "title" to "title",
        "description" to "description",
        "language" to "language",
        "publisher" to "publisher",
        "date" to "publishedYear",
        "subject" to "genre",
      ).forEach { (source, target) ->
        put(target, elements(source).joinToString(", ") { it.textContent.trim() })
      }
      val creators = elements("creator")

      fun role(element: Element) =
        element.getAttributeNS("http://www.idpf.org/2007/opf", "role").ifBlank {
          element.getAttribute("role")
        }
      put("author", creators.filter { role(it) in setOf("", "aut") }.joinToString(", ") { it.textContent.trim() })
      put("narrator", creators.filter { role(it) == "nrt" }.joinToString(", ") { it.textContent.trim() })
      elements("meta").forEach {
        when (it.getAttribute("name")) {
          "calibre:series" -> put("series", it.getAttribute("content"))
          "calibre:series_index" -> put("seriesPart", it.getAttribute("content"))
        }
      }
      elements("identifier").forEach {
        val scheme = it.getAttributeNS("http://www.idpf.org/2007/opf", "scheme").ifBlank { it.getAttribute("scheme") }
        if (scheme.equals("isbn", true)) put("isbn", it.textContent.trim())
        if (scheme.equals("asin", true)) put("asin", it.textContent.trim())
      }
    }
  }

  private fun saveCover(
    bytes: ByteArray?,
    sourceKey: String,
  ): String? {
    if (bytes == null || bytes.size > 16 * 1024 * 1024) return null
    return runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val options =
        BitmapFactory.Options().apply {
          inSampleSize = 1
          while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 800) inSampleSize *= 2
        }
      val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
      try {
        val file = File(context.filesDir, "audiobook_covers/${digest(sourceKey)}.jpg")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        Uri.fromFile(file).toString()
      } finally {
        bitmap.recycle()
      }
    }.getOrNull()
  }

  private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "").trim()

  private fun JSONObject.names(key: String): String =
    optJSONArray(key)?.let { values ->
      (0 until values.length())
        .mapNotNull { index ->
          when (val value = values.opt(index)) {
            is String -> value
            is JSONObject -> value.text("name")
            else -> null
          }?.takeIf(String::isNotBlank)
        }.joinToString(", ")
    } ?: text(key)

  private fun digest(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
}
