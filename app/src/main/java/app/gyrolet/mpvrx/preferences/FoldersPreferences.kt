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

/** Each Home browser mode can independently opt out of expensive hidden-folder scans. */
enum class HiddenScanScope { LIBRARY, TREE, FOLDERS }

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
  // Video file paths pinned to the top of their folder listing
  val pinnedVideos = preferenceStore.getStringSet("pinned_videos", emptySet())

  /** Pins all [paths] unless every one is already pinned, in which case they are unpinned. */
  fun togglePinnedVideos(paths: Collection<String>) {
    if (paths.isEmpty()) return
    val current = pinnedVideos.get()
    pinnedVideos.set(if (current.containsAll(paths)) current - paths.toSet() else current + paths)
  }
  val includeNoMediaFolders = preferenceStore.getBoolean("include_nomedia_folders", false)
  /** Persisted SAF tree grants for the Play Store (scoped-storage) distribution. */
  val scopedHiddenFolderUris = preferenceStore.getStringSet("scoped_hidden_folder_uris", emptySet())
  val hiddenFolderMarkerNames =
    preferenceStore.getStringSet("hidden_folder_marker_names", setOf(".nomedia"))

  // Default true preserves existing users' hidden-folder choices. The original master
  // includeNoMediaFolders switch remains the global opt-in, including for SAF grants.
  val libraryScanMarkedFolders = preferenceStore.getBoolean("hidden_scan_library_markers", true)
  val libraryScanDotFolders = preferenceStore.getBoolean("hidden_scan_library_dot_folders", true)
  val treeScanMarkedFolders = preferenceStore.getBoolean("hidden_scan_tree_markers", true)
  val treeScanDotFolders = preferenceStore.getBoolean("hidden_scan_tree_dot_folders", true)
  val foldersScanMarkedFolders = preferenceStore.getBoolean("hidden_scan_folders_markers", true)
  val foldersScanDotFolders = preferenceStore.getBoolean("hidden_scan_folders_dot_folders", true)
  // Individual marker opt-outs per view; entries refer to the user's global marker list.
  val libraryIgnoredMarkers = preferenceStore.getStringSet("hidden_scan_library_ignored_markers", emptySet())
  val treeIgnoredMarkers = preferenceStore.getStringSet("hidden_scan_tree_ignored_markers", emptySet())
  val foldersIgnoredMarkers = preferenceStore.getStringSet("hidden_scan_folders_ignored_markers", emptySet())

  // A single compact control drives Library, Tree and Folders. Defaulting to the
  // intersection of the legacy per-view settings is a conservative one-time migration:
  // nothing the user previously disabled is silently re-enabled.
  val sharedScanMarkedFolders = preferenceStore.getBoolean(
    "hidden_scan_shared_markers",
    libraryScanMarkedFolders.get() && treeScanMarkedFolders.get() && foldersScanMarkedFolders.get(),
  )
  val sharedScanDotFolders = preferenceStore.getBoolean(
    "hidden_scan_shared_dot_folders",
    libraryScanDotFolders.get() && treeScanDotFolders.get() && foldersScanDotFolders.get(),
  )
  val sharedIgnoredMarkers = preferenceStore.getStringSet(
    "hidden_scan_shared_ignored_markers",
    libraryIgnoredMarkers.get() + treeIgnoredMarkers.get() + foldersIgnoredMarkers.get(),
  )

  // Compact connected selector under Hidden Folder Scan. Keep the marker/dot policies
  // shared while allowing each Home browser mode to skip hidden content independently.
  val libraryHiddenScanEnabled = preferenceStore.getBoolean("hidden_scan_enabled_library", true)
  val treeHiddenScanEnabled = preferenceStore.getBoolean("hidden_scan_enabled_tree", true)
  val foldersHiddenScanEnabled = preferenceStore.getBoolean("hidden_scan_enabled_folders", true)

  fun isModeScanEnabled(scope: HiddenScanScope): Boolean = when (scope) {
    HiddenScanScope.LIBRARY -> libraryHiddenScanEnabled.get()
    HiddenScanScope.TREE -> treeHiddenScanEnabled.get()
    HiddenScanScope.FOLDERS -> foldersHiddenScanEnabled.get()
  }

  // Keep the scope parameter for existing scanner callers; all three use one policy now.
  @Suppress("UNUSED_PARAMETER")
  fun ignoredMarkers(scope: HiddenScanScope): Set<String> = sharedIgnoredMarkers.get()

  @Suppress("UNUSED_PARAMETER")
  fun scanMarkedFolders(scope: HiddenScanScope): Boolean = sharedScanMarkedFolders.get()

  @Suppress("UNUSED_PARAMETER")
  fun scanDotFolders(scope: HiddenScanScope): Boolean = sharedScanDotFolders.get()

  fun enabledMarkerNames(scope: HiddenScanScope): Set<String> =
    if (scanMarkedFolders(scope)) hiddenFolderMarkerNames.get() - ignoredMarkers(scope) else emptySet()

  fun hiddenScanEnabled(scope: HiddenScanScope): Boolean =
    includeNoMediaFolders.get() && isModeScanEnabled(scope) &&
      (enabledMarkerNames(scope).isNotEmpty() || scanDotFolders(scope))

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
