/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.artwork

import java.text.Normalizer
import java.util.Locale

internal data class MotionArtworkQuery(val title: String, val artist: String, val album: String?)

/** Online providers, in the order they are asked. Still artwork stays underneath as the final fallback. */
internal enum class MotionArtworkSource { APPLE_MUSIC, TIDAL }

internal data class MotionArtworkRendition(val url: String, val hls: Boolean)

internal data class RemoteMotionArtwork(
  val source: MotionArtworkSource,
  val renditions: List<MotionArtworkRendition>,
)

internal interface MotionArtworkProvider {
  suspend fun find(query: MotionArtworkQuery): RemoteMotionArtwork?
}

internal class MotionArtworkResolver(
  private val appleMusic: AppleMusicArtworkRepository,
  private val tidal: TidalArtworkRepository,
) {
  /** First provider in [sources] order with artwork for [query]; null keeps the still cover. */
  suspend fun find(query: MotionArtworkQuery, sources: List<MotionArtworkSource>): RemoteMotionArtwork? {
    for (source in sources) {
      val provider = when (source) {
        MotionArtworkSource.APPLE_MUSIC -> appleMusic
        MotionArtworkSource.TIDAL -> tidal
      }
      provider.find(query)?.let { return it }
    }
    return null
  }

  companion object {
    fun sources(
      appleMusic: Boolean,
      tidal: Boolean,
    ): List<MotionArtworkSource> = buildList {
      if (appleMusic) add(MotionArtworkSource.APPLE_MUSIC)
      if (tidal) add(MotionArtworkSource.TIDAL)
    }
  }
}

/** Title/artist matching shared by catalogue providers, so each accepts the same songs. */
internal object MotionArtworkMatching {
  private val ARTIST_SEPARATOR = Regex("""\s+(?:feat\.?|ft\.?|featuring|with|&|and|x)\s+|[,;]""", RegexOption.IGNORE_CASE)
  private val FEATURED_CREDIT = Regex("""[\[(](?:feat\.?|ft\.?|featuring)\s+([^)\]]+)[\])]""", RegexOption.IGNORE_CASE)
  val TITLE_DECORATION = Regex(
    """\s*[\[(](?:official\s+(?:music\s+)?(?:video|audio|visuali[sz]er)|lyric(?:s|\s+video)?|audio|video|visuali[sz]er|(?:feat\.?|ft\.?|featuring)\s+[^)\]]+)[\])]""",
    RegexOption.IGNORE_CASE,
  )

  fun normalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
      .lowercase(Locale.ROOT).replace(Regex("\\p{M}+"), "")
      .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

  fun cleanTitle(title: String): String = title.replace(TITLE_DECORATION, "").trim()

  fun titleMatches(query: MotionArtworkQuery, title: String): Boolean {
    val wanted = normalize(cleanTitle(query.title))
    return wanted.isNotBlank() && normalize(cleanTitle(title)) == wanted
  }

  /** Every artist the query credits must be credited by the candidate. */
  fun artistsMatch(query: MotionArtworkQuery, artist: String, title: String): Boolean {
    val wanted = artistCredits(query.artist, query.title)
    val credited = artistCredits(artist, title)
    return wanted.isNotEmpty() && wanted.all { it in credited }
  }

  /** 2 when the album tag agrees, else 1; edition labels must not veto a song. */
  fun score(query: MotionArtworkQuery, album: String?): Int {
    val wanted = query.album?.let(::normalize)?.takeIf(String::isNotBlank)
    return if (wanted != null && wanted == album?.let(::normalize)) 2 else 1
  }

  private fun artistCredits(artist: String, title: String): Set<String> =
    (sequenceOf(artist) + FEATURED_CREDIT.findAll(title).map { it.groupValues[1] })
      .flatMap { ARTIST_SEPARATOR.split(it).asSequence() }.map(::normalize).filter(String::isNotBlank).toSet()

  fun storefront(): String =
    Locale.getDefault().country.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2}")) } ?: "us"
}
