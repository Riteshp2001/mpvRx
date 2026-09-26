/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's Megalobiz provider
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider

/** Line-synced community LRC scraped from Megalobiz. */
internal object MegalobizProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.MEGALOBIZ

  private const val BASE = "https://www.megalobiz.com"

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    for (ref in query.candidates()) {
      val search = buildUrl("$BASE/searchall", "qry" to "${ref.artist} ${ref.title}".trim()) ?: continue
      val results = lyricsGet(search) ?: continue
      val path = LRC_LINK.find(results)?.groupValues?.get(1) ?: continue
      val page = lyricsGet(BASE + path.replace("&amp;", "&")) ?: continue
      val raw = LRC_BODY.find(page)?.groupValues?.get(1) ?: continue
      val lrc =
        raw
          .replace(Regex("""(?i)<br\s*/?>"""), "\n")
          .replace(Regex("<[^>]+>"), "")
          .let(::decodeEntities)
      parseLrcText(lrc)?.let { return it }
    }
    return null
  }

  private val LRC_LINK = Regex("""href=["'](/lrc/maker/download/[^"']+)["']""", RegexOption.IGNORE_CASE)
  private val LRC_BODY =
    Regex(
      """id=["']lrc_[^"']*_details["'][^>]*>(.*?)</span>""",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
