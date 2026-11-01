/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** Manual check against a real (possibly truncated) MKV: set MKV_SAMPLE to its path. */
class MatroskaRealFileProbe {
  @Test
  fun dumpFirstCuesOfEachTextTrack() =
    runBlocking {
      val path = System.getenv("MKV_SAMPLE")
      assumeTrue(path != null && File(path).exists())
      RandomAccessFile(path, "r").use { file ->
        val source = FileChannelSource(file.channel)
        val tracks = MatroskaSubtitleExtractor.listSubtitleTracks(source)
        val out = StringBuilder()
        for ((ordinal, track) in tracks.withIndex()) {
          out.append("== track ${track.number} ${track.codecId} ${track.language} ${track.name}\n")
          if (!track.isText) continue
          val result = MatroskaSubtitleExtractor.extract(source, { it[ordinal] })
          out.append(result.content.take(1500)).append('\n')
        }
        File(System.getenv("MKV_SAMPLE_OUT") ?: "$path.txt").writeText(out.toString())
      }
    }
}
