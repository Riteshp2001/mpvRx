/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object ArtistImageRepository {
  private val cache = LruCache<String, String>(300)
  private val failed = mutableSetOf<String>()

  suspend fun getArtistImageUrl(client: OkHttpClient, artistName: String): String? {
    if (artistName.isBlank() || artistName.equals("Unknown Artist", ignoreCase = true)) return null
    val key = artistName.trim().lowercase()

    cache.get(key)?.let { return it }
    if (failed.contains(key)) return null

    return withContext(Dispatchers.IO) {
      try {
        val encoded = URLEncoder.encode(artistName.trim(), "UTF-8")
        val url = "https://api.deezer.com/search/artist?q=$encoded&limit=1"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            failed.add(key)
            return@withContext null
          }
          val body = response.body.string()
          val json = JSONObject(body)
          val data = json.optJSONArray("data")
          if (data != null && data.length() > 0) {
            val item = data.getJSONObject(0)
            val imageUrl = item.optString("picture_medium", "")
              .ifBlank { item.optString("picture_big", "") }
              .ifBlank { item.optString("picture_xl", "") }

            if (imageUrl.isNotBlank()) {
              cache.put(key, imageUrl)
              return@withContext imageUrl
            }
          }
          failed.add(key)
          null
        }
      } catch (e: Exception) {
        null
      }
    }
  }
}
