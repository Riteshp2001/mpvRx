/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.artwork

import java.text.Normalizer
import java.util.Locale

internal data class MotionArtworkQuery(
  val title: String,
  val artist: String,
  val album: String?,
)

/**
 * Online providers, in BitChord's default order: Spotify goes last because reaching it means
 * loading the web player. Still artwork stays underneath as the final fallback.
 */
internal enum class MotionArtworkSource { APPLE_MUSIC, TIDAL, SPOTIFY }

internal data class MotionArtworkRendition(
  val url: String,
  val hls: Boolean,
)

/**
 * [renditions] in order of preference, square cuts first: every surface centre-crops the clip to
 * fill it, and providers' tall cuts often inset the square cover on a plain backdrop.
 */
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
  private val spotify: SpotifyArtworkRepository,
) {
  private data class Key(
    val query: MotionArtworkQuery,
    val sources: List<MotionArtworkSource>,
  )

  private val hits =
    object : LinkedHashMap<Key, RemoteMotionArtwork>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, RemoteMotionArtwork>?): Boolean = size > 64
    }

  /** A clip already found for [query] this session, without touching the network. */
  fun cached(
    query: MotionArtworkQuery,
    sources: List<MotionArtworkSource>,
  ): RemoteMotionArtwork? = synchronized(hits) { hits[Key(query, sources)] }

  /** First provider in [sources] order with artwork for [query]; null keeps the still cover. */
  suspend fun find(
    query: MotionArtworkQuery,
    sources: List<MotionArtworkSource>,
  ): RemoteMotionArtwork? {
    for (source in sources) {
      val provider =
        when (source) {
          MotionArtworkSource.APPLE_MUSIC -> appleMusic
          MotionArtworkSource.TIDAL -> tidal
          MotionArtworkSource.SPOTIFY -> spotify
        }
      provider.find(query)?.let { found ->
        synchronized(hits) { hits[Key(query, sources)] = found }
        return found
      }
    }
    return null
  }

  companion object {
    fun sources(
      appleMusic: Boolean,
      tidal: Boolean,
      spotify: Boolean,
    ): List<MotionArtworkSource> =
      buildList {
        if (appleMusic) add(MotionArtworkSource.APPLE_MUSIC)
        if (tidal) add(MotionArtworkSource.TIDAL)
        if (spotify) add(MotionArtworkSource.SPOTIFY)
      }
  }
}

/** Title/artist matching shared by catalogue providers, so each accepts the same songs. */
internal object MotionArtworkMatching {
  private val ARTIST_SEPARATOR =
    Regex("""\s+(?:feat\.?|ft\.?|featuring|with|&|and|x)\s+|[,;]""", RegexOption.IGNORE_CASE)
  private val FEATURED_CREDIT = Regex("""[\[(](?:feat\.?|ft\.?|featuring)\s+([^)\]]+)[\])]""", RegexOption.IGNORE_CASE)
  val TITLE_DECORATION =
    Regex(
      """\s*[\[(](?:official\s+(?:music\s+)?(?:video|audio|visuali[sz]er)|lyric(?:s|\s+video)?|audio|video|visuali[sz]er|(?:feat\.?|ft\.?|featuring)\s+[^)\]]+)[\])]""",
      RegexOption.IGNORE_CASE,
    )

  fun normalize(value: String): String =
    Normalizer
      .normalize(value, Normalizer.Form.NFKD)
      .lowercase(Locale.ROOT)
      .replace(Regex("\\p{M}+"), "")
      .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
      .trim()

  fun cleanTitle(title: String): String = title.replace(TITLE_DECORATION, "").trim()

  fun titleMatches(
    query: MotionArtworkQuery,
    title: String,
  ): Boolean {
    val wanted = normalize(cleanTitle(query.title))
    return wanted.isNotBlank() && normalize(cleanTitle(title)) == wanted
  }

  /** Every artist the query credits must be credited by the candidate. */
  fun artistsMatch(
    query: MotionArtworkQuery,
    artist: String,
    title: String,
  ): Boolean {
    val wanted = artistCredits(query.artist, query.title)
    val credited = artistCredits(artist, title)
    return wanted.isNotEmpty() && wanted.all { it in credited }
  }

  /**
   * Whether [album] is the release the query's album tag names. Edition and format labels
   * ("(Remastered 2009)", "[Deluxe Edition]", " - Single") are ignored; "(Taylor's Version)"
   * style re-recordings are not, since their covers differ.
   */
  fun albumMatches(
    query: MotionArtworkQuery,
    album: String?,
  ): Boolean {
    val wanted = query.album?.let { normalize(cleanAlbum(it)) }?.takeIf(String::isNotBlank) ?: return false
    return wanted == album?.let { normalize(cleanAlbum(it)) }
  }

  /** 2 when the album tag agrees, else 1; edition labels must not veto a song. */
  fun score(
    query: MotionArtworkQuery,
    album: String?,
  ): Int = if (albumMatches(query, album)) 2 else 1

  private val ALBUM_EDITION =
    Regex(
      """\s*[\[(][^\])]*\b(?:remaster(?:ed)?|deluxe|edition|expanded|anniversary|bonus|explicit|clean)\b[^\])]*[\])]|\s+-\s+(?:single|ep)$""",
      RegexOption.IGNORE_CASE,
    )

  private fun cleanAlbum(album: String): String = album.replace(ALBUM_EDITION, "").trim()

  private fun artistCredits(
    artist: String,
    title: String,
  ): Set<String> =
    (sequenceOf(artist) + FEATURED_CREDIT.findAll(title).map { it.groupValues[1] })
      .flatMap { ARTIST_SEPARATOR.split(it).asSequence() }
      .map(::normalize)
      .filter(String::isNotBlank)
      .toSet()

  fun storefront(): String =
    Locale
      .getDefault()
      .country
      .lowercase(Locale.ROOT)
      .takeIf { it.matches(Regex("[a-z]{2}")) } ?: "us"
}
