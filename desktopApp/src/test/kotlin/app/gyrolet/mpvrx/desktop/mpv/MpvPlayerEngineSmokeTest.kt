package app.gyrolet.mpvrx.desktop.mpv

import app.gyrolet.mpvrx.core.player.LoadOptions
import app.gyrolet.mpvrx.core.player.TrackType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Smoke test against the real libmpv; skipped when the native library is not installed. */
class MpvPlayerEngineSmokeTest {
  @Test
  fun rendersFramesAndReportsState() {
    val lib = LibMpv.load().getOrNull() ?: return
    val temp = Files.createTempDirectory("mpvrx-test").toFile()
    val engine =
      MpvPlayerEngine(
        lib.api,
        MpvEngineConfig(temp, temp, hardwareDecoding = false, extraOptions = "", logFile = null),
      )
    try {
      engine.setSurfaceSize(320, 180)
      engine.load("av://lavfi:testsrc2=size=640x360:rate=25:duration=30", LoadOptions(startSeconds = 2.0))
      val frame = waitFor(10_000) { engine.takeFrame() }
      assertNotNull(frame, "no video frame rendered")
      assertTrue(frame.width == 320 && frame.height == 180, "frame ${frame.width}x${frame.height}")
      frame.close()
      waitFor(5_000) { engine.state.value.takeIf { it.durationSeconds > 0 && it.videoWidth > 0 } }
      val state = engine.state.value
      // lavfi sources only report an estimated duration.
      assertTrue(state.durationSeconds > 0, "duration ${state.durationSeconds}")
      assertTrue(state.videoWidth == 640, "width ${state.videoWidth}")
      assertTrue(state.tracks.any { it.type == TrackType.Video })
      assertTrue(!state.isAudioOnly)
      assertTrue(state.positionSeconds >= 1.9, "start option ignored: ${state.positionSeconds}")
      assertTrue(engine.screenshot(temp.absolutePath, includeSubtitles = false))
      waitFor(5_000) { temp.listFiles { f -> f.extension == "png" }?.firstOrNull() }
      assertTrue(temp.listFiles { f -> f.extension == "png" }.orEmpty().isNotEmpty(), "screenshot missing")
    } finally {
      engine.release()
      temp.deleteRecursively()
    }
  }

  /** Software rendering must not change the playback clock (MPVRX_TEST_MEDIA overrides the source). */
  @Test
  fun playsInRealTime() {
    val lib = LibMpv.load().getOrNull() ?: return
    val temp = Files.createTempDirectory("mpvrx-test").toFile()
    val engine = MpvPlayerEngine(lib.api, MpvEngineConfig(temp, temp, hardwareDecoding = false, extraOptions = "", logFile = null))
    try {
      engine.setSurfaceSize(640, 360)
      val source = System.getenv("MPVRX_TEST_MEDIA") ?: "av://lavfi:testsrc2=size=640x360:rate=30:duration=60"
      engine.load(source, LoadOptions())
      waitFor(10_000) { engine.state.value.positionSeconds.takeIf { it > 0.3 } }
      val start = engine.state.value.positionSeconds
      // Drain frames like the UI would.
      val until = System.currentTimeMillis() + 3_000
      while (System.currentTimeMillis() < until) {
        engine.takeFrame()?.close()
        Thread.sleep(16)
      }
      val elapsed = engine.state.value.positionSeconds - start
      assertTrue(elapsed in 2.0..4.5, "advanced $elapsed s in 3 s wall time")
    } finally {
      engine.release()
      temp.deleteRecursively()
    }
  }

  private fun <T> waitFor(timeoutMs: Long, probe: () -> T?): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      probe()?.let { return it }
      Thread.sleep(50)
    }
    return null
  }
}
