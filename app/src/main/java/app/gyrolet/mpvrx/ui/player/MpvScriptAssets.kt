/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player

import java.io.File

/** Keep app-generated scripts out of mpv's user-script autoload directory and cache inventory. */
internal object MpvScriptAssets {
  fun generatedDirectory(filesDir: File): File = File(filesDir, "mpvrx-generated-scripts")

  fun userScriptNames(filesDir: File): Set<String> =
    File(filesDir, "scripts").listFiles()
      ?.asSequence()
      ?.filter { it.isFile && it.extension.lowercase() in setOf("lua", "js") }
      ?.map(File::getName)
      ?.toSet()
      .orEmpty()

  /**
   * Mirror require() modules from the already-synced local helper folders. This avoids a second
   * recursive SAF walk and preserves unchanged files instead of deleting/rebuilding the cache.
   * Top-level user scripts are entry points, not modules, so only helper folders are mirrored.
   */
  fun syncLuaModules(scriptsDir: File, modulesDir: File, helperDirectories: Set<String>? = null): Int {
    check(modulesDir.isDirectory || modulesDir.mkdirs()) { "Could not create Lua module directory" }
    val expectedFiles = mutableSetOf<String>()
    val expectedDirectories = mutableSetOf<String>()
    var copied = 0

    fun syncDirectory(source: File, destination: File, relativePath: String) {
      expectedDirectories += relativePath
      check(destination.isDirectory || destination.mkdirs()) { "Could not create Lua module folder" }
      val children = checkNotNull(source.listFiles()) { "Could not read cached script folder" }
      children.forEach { file ->
        val path = "$relativePath/${file.name}"
        when {
          file.isDirectory -> syncDirectory(file, File(destination, file.name), path)
          file.isFile && file.extension.equals("lua", ignoreCase = true) -> {
            expectedFiles += path
            val target = File(destination, file.name)
            if (!target.isFile || target.length() != file.length() || target.lastModified() != file.lastModified()) {
              file.copyTo(target, overwrite = true)
              target.setLastModified(file.lastModified())
              copied++
            }
          }
        }
      }
    }

    val folders = checkNotNull(scriptsDir.listFiles()) { "Could not read cached scripts" }
    folders.filter { it.isDirectory && (helperDirectories == null || it.name in helperDirectories) }.forEach { folder ->
      syncDirectory(folder, File(modulesDir, folder.name), folder.name)
    }
    // Prune after the complete source inventory succeeds. A failed read must not erase modules.
    modulesDir.walkBottomUp().filter { it != modulesDir }.forEach { file ->
      val path = file.relativeTo(modulesDir).invariantSeparatorsPath
      if ((file.isFile && path !in expectedFiles) || (file.isDirectory && path !in expectedDirectories)) {
        check(file.delete()) { "Could not remove stale Lua module: $path" }
      }
    }
    return copied
  }
}
