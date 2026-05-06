package app.gyrolet.mpvrx.repository.subtitlecat

import org.jsoup.Jsoup

data class SubtitleCatSearchResult(
  val title: String,
  val path: String,
  val size: String? = null,
  val downloads: Int = 0,
)

data class SubtitleCatDownloadLink(
  val languageCode: String,
  val path: String,
  val languageLabel: String? = null,
  val fileName: String? = null,
)

object SubtitleCatHtmlParser {
  fun parseSearchResults(html: String): List<SubtitleCatSearchResult> {
    val doc = Jsoup.parse(html)
    return doc.select("table.sub-table tbody tr").mapNotNull { row ->
      val cells = row.select("td")
      val anchor = cells.firstOrNull()?.selectFirst("a[href]") ?: return@mapNotNull null
      val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val path = anchor.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val size = cells.getOrNull(2)?.text()?.trim()?.removePrefix("SIZE")?.trim()?.takeIf { it.isNotBlank() }
      val downloads =
        cells.getOrNull(3)
          ?.text()
          ?.trim()
          ?.split(Regex("""\s+"""))
          ?.firstOrNull()
          ?.toIntOrNull()
          ?: 0

      SubtitleCatSearchResult(
        title = title,
        path = path,
        size = size,
        downloads = downloads,
      )
    }
  }

  fun parseDownloadLinks(html: String): List<SubtitleCatDownloadLink> {
    val doc = Jsoup.parse(html)
    val legacyLinks =
      doc.select("a[id^=download_][href]").mapNotNull { anchor ->
        val code = anchor.id().removePrefix("download_").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SubtitleCatDownloadLink(
          languageCode = code,
          path = anchor.attr("href"),
          languageLabel = anchor.text().trim().takeIf { it.isNotBlank() },
          fileName = anchor.attr("href").substringBefore("?").substringAfterLast("/").takeIf { it.isNotBlank() },
        )
      }

    val blockLinks =
      doc.select("div.sub-single").mapNotNull { block ->
        val code = block.selectFirst("span img[alt]")?.attr("alt")?.takeIf { it.isNotBlank() }
        val label = block.select("span").getOrNull(1)?.text()?.trim()?.takeIf { it.isNotBlank() }
        val anchor = block.selectFirst("a[href]") ?: return@mapNotNull null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SubtitleCatDownloadLink(
          languageCode = code ?: label ?: "und",
          path = href,
          languageLabel = label,
          fileName = href.substringBefore("?").substringAfterLast("/").takeIf { it.isNotBlank() },
        )
      }

    return (legacyLinks + blockLinks).distinctBy { it.languageCode.lowercase() to it.path }
  }
}
