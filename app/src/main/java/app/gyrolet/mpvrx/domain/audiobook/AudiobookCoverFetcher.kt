/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.domain.audiobook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.gyrolet.mpvrx.network.awaitResponse
import app.gyrolet.mpvrx.utils.telemetry.withMeasureTelemetry
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.GlobalContext

data class AudiobookOnlineMetadata(
  val title: String,
  val author: String? = null,
  val narrator: String? = null,
  val description: String? = null,
  val publishedYear: String? = null,
  val genre: String? = null,
  val coverUrl: String,
  val provider: String = "iTunes",
)

object AudiobookCoverFetcher {

  private val json = Json { ignoreUnknownKeys = true }

  private fun getClient(): OkHttpClient {
    return runCatching { GlobalContext.get().get<OkHttpClient>() }.getOrNull() ?: OkHttpClient.Builder().withMeasureTelemetry().build()
  }

  suspend fun search(
    title: String,
    author: String? = null,
    client: OkHttpClient = getClient(),
  ): List<AudiobookOnlineMetadata> = withContext(Dispatchers.IO) {
    if (title.isBlank()) return@withContext emptyList()

    // 1. Primary provider: Apple Books / iTunes Search API
    val itunesResults = searchItunes(title, author, client)
    if (itunesResults.isNotEmpty()) {
      return@withContext itunesResults
    }

    // 2. Fallback provider: Open Library
    searchOpenLibrary(title, author, client)
  }

  private suspend fun searchItunes(
    title: String,
    author: String?,
    client: OkHttpClient,
  ): List<AudiobookOnlineMetadata> = withContext(Dispatchers.IO) {
    try {
      val queryTerms = listOfNotNull(
        title.trim().takeIf(String::isNotBlank),
        author?.trim()?.takeIf(String::isNotBlank),
      ).joinToString(" ")

      val encoded = URLEncoder.encode(queryTerms, "UTF-8")
      val url = "https://itunes.apple.com/search?term=$encoded&media=audiobook&entity=audiobook&limit=15"
      val request = Request.Builder()
        .url(url)
        .header("User-Agent", "mpvRx/AudiobookCoverFetcher")
        .build()

      val response = client.newCall(request).awaitResponse()
      if (!response.isSuccessful) return@withContext emptyList()

      val bodyStr = response.body.string()
      val root = json.parseToJsonElement(bodyStr).jsonObject
      val results = root["results"]?.jsonArray ?: return@withContext emptyList()

      results.mapNotNull { element ->
        val obj = element.jsonObject
        val rawCover = obj["artworkUrl100"]?.jsonPrimitive?.content
          ?: obj["artworkUrl60"]?.jsonPrimitive?.content
          ?: return@mapNotNull null

        // Convert standard 100x100 artwork to crisp 1400x1400 square artwork from Apple's CDN
        val highResCover = rawCover
          .replace("100x100bb.jpg", "1400x1400bb.jpg")
          .replace("60x60bb.jpg", "1400x1400bb.jpg")

        val collectionName = obj["collectionName"]?.jsonPrimitive?.content ?: obj["trackName"]?.jsonPrimitive?.content ?: ""
        val artistName = obj["artistName"]?.jsonPrimitive?.content
        val description = obj["description"]?.jsonPrimitive?.content
        val primaryGenreName = obj["primaryGenreName"]?.jsonPrimitive?.content
        val releaseDate = obj["releaseDate"]?.jsonPrimitive?.content?.take(4)

        AudiobookOnlineMetadata(
          title = collectionName.ifBlank { title },
          author = artistName,
          narrator = description?.let { extractNarrator(it) },
          description = description,
          publishedYear = releaseDate,
          genre = primaryGenreName,
          coverUrl = highResCover,
          provider = "Apple Books / iTunes",
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  private suspend fun searchOpenLibrary(
    title: String,
    author: String?,
    client: OkHttpClient,
  ): List<AudiobookOnlineMetadata> = withContext(Dispatchers.IO) {
    try {
      val encodedTitle = URLEncoder.encode(title.trim(), "UTF-8")
      val authorParam = if (!author.isNullOrBlank()) "&author=" + URLEncoder.encode(author.trim(), "UTF-8") else ""
      val url = "https://openlibrary.org/search.json?title=$encodedTitle$authorParam&limit=10"

      val request = Request.Builder()
        .url(url)
        .header("User-Agent", "mpvRx/AudiobookCoverFetcher")
        .build()

      val response = client.newCall(request).awaitResponse()
      if (!response.isSuccessful) return@withContext emptyList()

      val bodyStr = response.body.string()
      val root = json.parseToJsonElement(bodyStr).jsonObject
      val docs = root["docs"]?.jsonArray ?: return@withContext emptyList()

      docs.mapNotNull { docElement ->
        val obj = docElement.jsonObject
        val coverId = obj["cover_i"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val coverUrl = "https://covers.openlibrary.org/b/id/$coverId-L.jpg"

        val docTitle = obj["title"]?.jsonPrimitive?.content ?: title
        val authors = obj["author_name"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.joinToString(", ")
        val firstPublishYear = obj["first_publish_year"]?.jsonPrimitive?.content

        AudiobookOnlineMetadata(
          title = docTitle,
          author = authors ?: author,
          narrator = null,
          description = null,
          publishedYear = firstPublishYear,
          genre = null,
          coverUrl = coverUrl,
          provider = "Open Library",
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  suspend fun downloadAndSaveCover(
    context: Context,
    coverUrl: String,
    sourceKey: String,
    client: OkHttpClient = getClient(),
  ): String? = withContext(Dispatchers.IO) {
    try {
      val request = Request.Builder()
        .url(coverUrl)
        .header("User-Agent", "mpvRx/AudiobookCoverFetcher")
        .build()

      val response = client.newCall(request).awaitResponse()
      if (!response.isSuccessful) return@withContext null

      val bytes = response.body.bytes()
      if (bytes.isEmpty() || bytes.size > 16 * 1024 * 1024) return@withContext null

      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1200) inSampleSize *= 2
      }
      val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null

      try {
        val file = File(context.filesDir, "audiobook_covers/${digest(sourceKey)}.jpg")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Uri.fromFile(file).toString()
      } finally {
        bitmap.recycle()
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun extractNarrator(description: String): String? {
    val regex = Regex("""(?i)(?:narrated|read|narrator)\s+by\s+([A-Za-z\s,\.\-&]+?)(?:\.|\n|<|$)""")
    val match = regex.find(description)
    return match?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
  }

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
