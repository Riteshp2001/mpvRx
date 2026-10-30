/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder

// Holds pre-processed video data for fast searching
private data class VideoIndex(
  val video: Video,
  val nameLower: String,
  val tokens: List<String>,
)

// Holds pre-processed folder data for fast searching
private data class FolderIndex(
  val folder: VideoFolder,
  val nameLower: String,
  val tokens: List<String>,
)

object MediaSearchEngine {
  private data class Index(
    val videos: List<VideoIndex> = emptyList(),
    val folders: List<FolderIndex> = emptyList(),
  )

  // Publish a complete immutable snapshot; a search must never iterate maps being rebuilt.
  @Volatile private var index = Index()

  // -------------------------
  // INDEXING
  // -------------------------

  /**
   * Builds the search index. Call this ONCE when entering the search screen.
   * Pre-lowercasing and tokenizing here prevents expensive string operations during typing.
   */
  fun buildIndex(
    folders: List<VideoFolder>,
    videosByFolder: Map<String, List<Video>>,
  ) {
    val videoMap = HashMap<String, VideoIndex>()
    val folderMap = HashMap<String, FolderIndex>()

    for (folder in folders) {
      val searchableText = "${folder.name} ${folder.path}".lowercase()
      folderMap[folder.path] =
        FolderIndex(
          folder = folder,
          nameLower = searchableText,
          tokens = tokenize(searchableText),
        )

      // Optimization: Skip folders with no videos to save memory and loop time
      val videos = videosByFolder[folder.bucketId] ?: continue
      for (video in videos) {
        val vName = video.displayName.lowercase()
        videoMap[video.path] =
          VideoIndex(
            video = video,
            nameLower = vName,
            tokens = tokenize(vName),
          )
      }
    }
    index = Index(videoMap.values.toList(), folderMap.values.toList())
  }

  // -------------------------
  // SEARCH
  // -------------------------

  /**
   * Searches the pre-built index.
   * Enforces left-to-right matching and filters out zero-score results before sorting
   * to ensure maximum performance on every keystroke.
   */
  fun search(
    query: String,
    limit: Int = 50,
  ): List<Any> {
    if (query.isBlank() || limit <= 0) return emptyList()

    val q = query.trim().lowercase()
    val qTokens = tokenize(q)

    val snapshot = index
    val results = ArrayList<Pair<Any, Int>>(minOf(limit, 50))

    for (folder in snapshot.folders) {
      val score = score(folder.nameLower, folder.tokens, q, qTokens)
      if (score > 0) results.add(folder.folder to score)
    }

    for (video in snapshot.videos) {
      val score = score(video.nameLower, video.tokens, q, qTokens)
      if (score > 0) results.add(video.video to score)
    }

    results.sortByDescending { it.second }
    return results.take(limit).map { it.first }
  }

  /** Searches a supplied video collection using the same fuzzy scoring as global search. */
  fun searchVideos(
    query: String,
    videos: List<Video>,
    limit: Int = 50,
  ): List<Video> {
    if (query.isBlank() || limit <= 0) return emptyList()

    val q = query.trim().lowercase()
    val qTokens = tokenize(q)
    val results = ArrayList<Pair<Video, Int>>(minOf(limit, 50))
    for (video in videos) {
      val name = video.displayName.lowercase()
      val score = score(name, tokenize(name), q, qTokens)
      if (score > 0) results.add(video to score)
    }
    results.sortByDescending { it.second }
    return results.take(limit).map { it.first }
  }

  // -------------------------
  // SCORING & HELPERS
  // -------------------------

  /**
   * Calculates a relevance score.
   * Enforces strict left-to-right matching for both full strings and individual tokens.   * Uses early exits to skip unnecessary calculations once a high score is achieved or a match fails.
   */
  private fun score(
    text: String,
    tokens: List<String>,
    query: String,
    qTokens: List<String>,
  ): Int {
    // 1. Exact match: Highest possible score, return immediately
    if (text == query) return 1000

    var score = 0

    // 2. Strict left-to-right prefix match
    if (text.startsWith(query)) {
      score += 200
      // Optimization: If the query is long enough, a prefix match is practically perfect.
      // Skip the heavier token loops entirely to save CPU cycles.
      if (query.length > 3) return score
    }

    // 3. Token matching: Enforces strict left-to-right word order
    var lastMatchedIndex = -1
    for (qt in qTokens) {
      var found = false

      // Only search for the current query token AFTER the previously matched token
      for (i in (lastMatchedIndex + 1) until tokens.size) {
        val t = tokens[i]
        if (t == qt) {
          score += 80
          lastMatchedIndex = i
          found = true
          break // Move to the next query token
        } else if (t.startsWith(qt)) {
          score += 40
          lastMatchedIndex = i
          found = true
          break // Move to the next query token
        }
      }

      // Optimization: Early exit. If a query token is not found in the remaining text,
      // the left-to-right sequence is broken. Stop checking and return current score.
      if (!found) return score
    }

    // 4. Sequential character match (fuzzy left-to-right, e.g., "bg prb" matches "big problem")
    if (isSequentialMatch(text, query)) score += 60
    return score
  }

  /**
   * Splits text into searchable tokens.
   * Optimization: split() with vararg chars is significantly faster than Regex.
   */
  private fun tokenize(text: String): List<String> =
    text.split(' ', '_', '-', '.', '/').filter { it.isNotEmpty() }

  /**
   * Checks if the query characters appear in the text in the exact same order,
   * even if other characters are between them (e.g., query "bg" matches "biG").
   * Optimization: Cached query.length to avoid repeated property access in the loop.
   */
  private fun isSequentialMatch(
    text: String,
    query: String,
  ): Boolean {
    var i = 0
    val qLen = query.length
    for (c in text) {
      if (i < qLen && c == query[i]) {
        i++
      }
    }
    return i == qLen
  }
}
