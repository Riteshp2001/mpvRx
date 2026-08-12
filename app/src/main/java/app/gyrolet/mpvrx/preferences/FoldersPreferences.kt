/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

enum class BlacklistScope {
  BOTH,
  VIDEO_ONLY,
  AUDIO_ONLY
}

/**
 * Preferences for folder management
 */
class FoldersPreferences(
  preferenceStore: PreferenceStore,
) {
  // Root folder from which all app storage paths are derived
  val baseStorageFolder = preferenceStore.getString("base_storage_folder", "")

  // Set of folder paths that should be hidden from the video folder list
  val blacklistedFolders = preferenceStore.getStringSet("blacklisted_folders", emptySet())
  // Set of folder paths that should be hidden from the audio/music library
  val blacklistedAudioFolders = preferenceStore.getStringSet("blacklisted_audio_folders", emptySet())
  val pinnedFolders = preferenceStore.getStringSet("pinned_folders", emptySet())
  val includeNoMediaFolders = preferenceStore.getBoolean("include_nomedia_folders", false)

  // Dedicated folder where downloaded movies are stored
  val movieFolder = preferenceStore.getString("movie_folder", "")

  fun addBlacklistedFolders(paths: Set<String>, scope: BlacklistScope) {
    val currentVideo = blacklistedFolders.get().toMutableSet()
    val currentAudio = blacklistedAudioFolders.get().toMutableSet()
    when (scope) {
      BlacklistScope.BOTH -> {
        currentVideo.addAll(paths)
        currentAudio.addAll(paths)
      }
      BlacklistScope.VIDEO_ONLY -> {
        currentVideo.addAll(paths)
        currentAudio.removeAll(paths)
      }
      BlacklistScope.AUDIO_ONLY -> {
        currentAudio.addAll(paths)
        currentVideo.removeAll(paths)
      }
    }
    blacklistedFolders.set(currentVideo)
    blacklistedAudioFolders.set(currentAudio)
  }

  fun removeBlacklistedFolder(path: String) {
    val currentVideo = blacklistedFolders.get().toMutableSet().apply { remove(path) }
    val currentAudio = blacklistedAudioFolders.get().toMutableSet().apply { remove(path) }
    blacklistedFolders.set(currentVideo)
    blacklistedAudioFolders.set(currentAudio)
  }

  fun removeBlacklistedFolders(paths: Set<String>) {
    val currentVideo = blacklistedFolders.get().toMutableSet().apply { removeAll(paths) }
    val currentAudio = blacklistedAudioFolders.get().toMutableSet().apply { removeAll(paths) }
    blacklistedFolders.set(currentVideo)
    blacklistedAudioFolders.set(currentAudio)
  }

  fun clearAllBlacklistedFolders() {
    blacklistedFolders.set(emptySet())
    blacklistedAudioFolders.set(emptySet())
  }
}
