/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.domain.recentlyplayed

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentlyPlayedMediaClassifierTest {
  @Test
  fun `classifies supported remote schemes case insensitively`() {
    assertTrue(RecentlyPlayedMediaClassifier.isRemote("HTTPS://example.com/video.mp4"))
    assertTrue(RecentlyPlayedMediaClassifier.isRemote("mpvrx-network://42/media/video.mkv"))
    assertTrue(RecentlyPlayedMediaClassifier.isRemote("rtmps://example.com/live"))
  }

  @Test
  fun `keeps local and content sources distinct from network sources`() {
    assertTrue(RecentlyPlayedMediaClassifier.isContentUri("content://media/external/video/12"))
    assertTrue(RecentlyPlayedMediaClassifier.isFileUri("file:///storage/emulated/0/video.mp4"))
    assertFalse(RecentlyPlayedMediaClassifier.isRemote("/storage/emulated/0/video.mp4"))
    assertFalse(RecentlyPlayedMediaClassifier.isRemote("C:\\Videos\\video.mp4"))
  }

  @Test
  fun `detects streaming playlist containers`() {
    assertTrue(RecentlyPlayedMediaClassifier.isStreamingPlaylist("https://example.com/master.m3u8"))
    assertTrue(RecentlyPlayedMediaClassifier.isStreamingPlaylist("https://example.com/dash/manifest"))
    assertFalse(RecentlyPlayedMediaClassifier.isStreamingPlaylist("https://example.com/movie.mp4"))
  }

  @Test
  fun `recognizes placeholder stream titles`() {
    assertTrue(RecentlyPlayedMediaClassifier.isGenericStreamName("Stream.mkv"))
    assertTrue(RecentlyPlayedMediaClassifier.isGenericStreamName("  "))
    assertFalse(RecentlyPlayedMediaClassifier.isGenericStreamName("My Movie"))
  }
}
