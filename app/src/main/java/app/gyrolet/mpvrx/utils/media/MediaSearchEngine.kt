/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.utils.media

import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder

// Holds pre-processed video data for fast searching
private data class VideoIndex(
  val video: Video,
  val nameLower: String,
  val tokens: List<String>
)

// Holds pre-processed folder data for fast searching
private data class FolderIndex(
  val folder: VideoFolder,
  val nameLower: String,
  val tokens: List<String>
)

object MediaSearchEngine {
  // Maps file/folder paths to their pre-processed index data
  private val videoMap = HashMap<String, VideoIndex>()
  private val folderMap = HashMap<String, FolderIndex>()

  // -------------------------
  // INDEXING
  // -------------------------
  /**
   * Builds the search index. Call this ONCE when entering the search screen.
   * Pre-lowercasing and tokenizing here prevents expensive string operations during typing.
   */
  fun buildIndex(
    folders: List<VideoFolder>,
    videosByFolder: Map<String, List<Video>>
  ) {
    videoMap.clear()
    folderMap.clear()

    for (folder in folders) {
      val name = folder.name
      folderMap[folder.path] = FolderIndex(
        folder = folder,
        nameLower = name.lowercase(),
        tokens = tokenize(name)
      )

      // Optimization: Skip folders with no videos to save memory and loop time
      val videos = videosByFolder[folder.bucketId] ?: continue
      for (video in videos) {
        val vName = video.displayName
        videoMap[video.path] = VideoIndex(
          video = video,
          nameLower = vName.lowercase(),
          tokens = tokenize(vName)
        )
      }
    }
  }

  // -------------------------
  // SEARCH
  // -------------------------
  /**
   * Searches the pre-built index. 
   * Enforces left-to-right matching and filters out zero-score results before sorting 
   * to ensure maximum performance on every keystroke.
   */
  fun search(query: String, limit: Int = 50): List<Any> {
    if (query.isBlank()) return emptyList()

    val q = query.lowercase()
    val qTokens = tokenize(q)
    
    // Optimization: Pre-allocate ArrayList capacity to avoid internal array resizing
    val results = ArrayList<Pair<Any, Int>>(videoMap.size + folderMap.size)

    for (folder in folderMap.values) {
      val score = score(folder.nameLower, folder.tokens, q, qTokens)
      if (score > 0) results.add(folder.folder to score)
    }

    for (video in videoMap.values) {
      val score = score(video.nameLower, video.tokens, q, qTokens)
      if (score > 0) results.add(video.video to score)
    }

    // Optimization: Filter out non-matches BEFORE sorting to drastically reduce sort time
    return results
      .filter { it.second > 0 }
      .sortedByDescending { it.second }
      .take(limit)
      .map { it.first }
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
    qTokens: List<String>
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
  private fun tokenize(text: String): List<String> {
    return text.lowercase().split(' ', '_', '-', '.', '/').filter { it.isNotEmpty() }
  }

  /**
   * Checks if the query characters appear in the text in the exact same order, 
   * even if other characters are between them (e.g., query "bg" matches "biG").
   * Optimization: Cached query.length to avoid repeated property access in the loop.
   */
  private fun isSequentialMatch(text: String, query: String): Boolean {
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