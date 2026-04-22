package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

/**
 * Preferences for folder management
 */
class FoldersPreferences(
  preferenceStore: PreferenceStore,
) {
  // Root folder from which all app storage paths are derived
  val baseStorageFolder = preferenceStore.getString("base_storage_folder", "")

  // Set of folder paths that should be hidden from the folder list
  val blacklistedFolders = preferenceStore.getStringSet("blacklisted_folders", emptySet())
  val pinnedFolders = preferenceStore.getStringSet("pinned_folders", emptySet())
  val includeNoMediaFolders = preferenceStore.getBoolean("include_nomedia_folders", false)

  // Dedicated folder where downloaded movies are stored
  val movieFolder = preferenceStore.getString("movie_folder", "")
}
