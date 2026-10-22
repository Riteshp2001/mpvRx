package app.gyrolet.mpvrx.desktop.mpv

import app.gyrolet.mpvrx.core.platform.FileTextStorage
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Needs libmpv and a local video in MPVRX_TEST_MEDIA; skipped otherwise. */
class MpvMediaInfoProviderSmokeTest {
  @Test
  fun probesMetadataAndThumbnail() {
    val lib = LibMpv.load().getOrNull() ?: return
    val media = System.getenv("MPVRX_TEST_MEDIA") ?: return
    val temp = Files.createTempDirectory("mpvrx-thumbs").toFile()
    val provider = MpvMediaInfoProvider(lib.api, temp, FileTextStorage(temp))
    try {
      runBlocking {
        val meta = provider.metadata(media)
        assertNotNull(meta)
        assertTrue(meta.durationSeconds > 0 && meta.width > 0, "metadata $meta")
        val thumbnail = provider.thumbnail(media, 320)
        assertNotNull(thumbnail, "thumbnail missing")
        assertTrue(thumbnail.width == 320, "thumbnail width ${thumbnail.width}")
      }
    } finally {
      provider.release()
      temp.deleteRecursively()
    }
  }
}
