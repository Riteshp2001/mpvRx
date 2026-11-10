package app.gyrolet.mpvrx.ui.player

import android.content.Context
import app.gyrolet.mpvrx.network.SharedHttpClient
import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class AutoEqEntry(val name: String, val url: String) {
  val measurementSource: String
    get() = url.toHttpUrlOrNull()?.pathSegments?.getOrNull(4).orEmpty()
}

internal data class AutoEqBand(val kind: String, val frequency: Double, val gain: Double, val q: Double)

internal data class AutoEqProfile(
  val entry: AutoEqEntry,
  val preampDb: Double,
  val bands: List<AutoEqBand>,
  val presetText: String,
) {
  fun saved(): String = Json.encodeToString(SavedAutoEq(entry.name, entry.url, presetText))

  fun mpvFilter(): String {
    val graph = buildList {
      add("volume=${preampDb.number()}dB")
      for (band in bands) {
        val filter = when (band.kind) {
          "PK" -> "equalizer"
          "LS", "LSC" -> "bass"
          "HS", "HSC" -> "treble"
          else -> error("Unsupported AutoEq filter")
        }
        add("$filter=f=${band.frequency.number()}:t=q:w=${band.q.number()}:g=${band.gain.number()}")
      }
    }.joinToString(",")
    return "@${AutoEq.FILTER_LABEL}:lavfi=[$graph]"
  }
}

@Serializable
private data class SavedAutoEq(val name: String, val url: String, val preset: String)

internal data class EqTargetPoint(val frequency: Double, val response: Double)

internal object AutoEq {
  const val FILTER_LABEL = "mpvrx-autoeq"
  private const val MAX_PRESET_CHARS = 65536
  private const val PRESET_PATH = "/gh/jaakkopasanen/AutoEq@master/results/"
  private val numberPattern = "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))"
  private val preampPattern = Regex("Preamp:\\s*$numberPattern\\s+dB", RegexOption.IGNORE_CASE)
  private val bandPattern = Regex(
    "Filter\\s+\\d+:\\s+(ON|OFF)\\s+([A-Z]+)\\s+Fc\\s+$numberPattern\\s+Hz\\s+Gain\\s+$numberPattern\\s+dB\\s+Q\\s+$numberPattern",
    RegexOption.IGNORE_CASE,
  )
  @Volatile private var cachedEntries: List<AutoEqEntry>? = null
  private val client by lazy {
    SharedHttpClient.derive {
      callTimeout(20, TimeUnit.SECONDS)
      connectTimeout(10, TimeUnit.SECONDS)
      readTimeout(15, TimeUnit.SECONDS)
      followRedirects(false)
      followSslRedirects(false)
    }
  }

  fun validUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.host == "cdn.jsdelivr.net" && url.port == 443 &&
      url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
      url.encodedPath.startsWith(PRESET_PATH) && url.encodedPath.endsWith("ParametricEQ.txt")
  }

  suspend fun entries(context: Context): List<AutoEqEntry> = withContext(Dispatchers.IO) {
    cachedEntries ?: context.assets.open("autoeq_index.tsv").bufferedReader().use { reader ->
      reader.lineSequence().mapNotNull { line ->
        val columns = line.trimStart('\uFEFF').split('\t', limit = 2)
        if (columns.size != 2 || columns[0].isBlank() || !validUrl(columns[1])) null
        else AutoEqEntry(columns[0].trim(), columns[1])
      }.distinctBy { it.url }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).toList()
    }.also { cachedEntries = it }
  }

  suspend fun download(entry: AutoEqEntry): AutoEqProfile = withContext(Dispatchers.IO) {
    require(validUrl(entry.url))
    client.newCall(Request.Builder().url(entry.url).build()).awaitResponse().use { response ->
      check(response.isSuccessful)
      val body = checkNotNull(response.body)
      check(body.contentLength() <= MAX_PRESET_CHARS)
      val text = body.charStream().use { reader ->
        val buffer = CharArray(MAX_PRESET_CHARS + 1)
        var count = 0
        while (count < buffer.size) {
          val read = reader.read(buffer, count, buffer.size - count)
          if (read < 0) break
          count += read
        }
        require(count <= MAX_PRESET_CHARS)
        String(buffer, 0, count)
      }
      parse(entry, text)
    }
  }

  fun restore(value: String): AutoEqProfile? {
    if (value.isBlank() || value.length > MAX_PRESET_CHARS * 2) return null
    return runCatching {
      val saved = Json.decodeFromString<SavedAutoEq>(value)
      parse(AutoEqEntry(saved.name, saved.url), saved.preset)
    }.getOrNull()
  }

  fun parse(entry: AutoEqEntry, text: String): AutoEqProfile {
    require(entry.name.isNotBlank() && entry.name.length <= 512 && validUrl(entry.url))
    require(text.length <= MAX_PRESET_CHARS)
    var preamp: Double? = null
    val bands = mutableListOf<AutoEqBand>()
    for (line in text.lineSequence().map(String::trim).filter { it.isNotBlank() && !it.startsWith('#') }) {
      val preampMatch = preampPattern.matchEntire(line)
      if (preampMatch != null) {
        require(preamp == null)
        preamp = preampMatch.groupValues[1].toDouble().also { require(it.isFinite() && it in -60.0..20.0) }
        continue
      }
      val match = requireNotNull(bandPattern.matchEntire(line))
      if (match.groupValues[1].equals("OFF", ignoreCase = true)) continue
      val kind = match.groupValues[2].uppercase(Locale.ROOT)
      val frequency = match.groupValues[3].toDouble()
      val gain = match.groupValues[4].toDouble()
      val q = match.groupValues[5].toDouble()
      require(kind in setOf("PK", "LS", "LSC", "HS", "HSC"))
      require(frequency.isFinite() && frequency in 10.0..22000.0)
      require(gain.isFinite() && gain in -24.0..24.0)
      require(q.isFinite() && q in 0.01..30.0)
      bands += AutoEqBand(kind, frequency, gain, q)
      require(bands.size <= 32)
    }
    require(bands.isNotEmpty())
    return AutoEqProfile(entry, requireNotNull(preamp), bands, text)
  }

  suspend fun target(context: Context, inEar: Boolean): List<EqTargetPoint> = withContext(Dispatchers.IO) {
    val asset = if (inEar) "harman_in_ear_2019v2.csv" else "harman_over_ear_2018.csv"
    context.assets.open("eq_targets/$asset").bufferedReader().use { reader ->
      val lines = reader.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.iterator()
      require(lines.hasNext() && lines.next() == "frequency,raw")
      val points = lines.asSequence().map { line ->
        val columns = line.split(',')
        require(columns.size == 2)
        EqTargetPoint(columns[0].toDouble(), columns[1].toDouble()).also {
          require(it.frequency.isFinite() && it.frequency > 0 && it.response.isFinite())
        }
      }.toList()
      require(points.size >= 2 && points.zipWithNext().all { (left, right) -> left.frequency < right.frequency })
      points
    }
  }
}

private fun Double.number(): String = String.format(Locale.ROOT, "%.6f", this)