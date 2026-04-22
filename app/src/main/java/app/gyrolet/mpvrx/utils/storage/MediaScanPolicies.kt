package app.gyrolet.mpvrx.utils.storage

import java.io.File
import java.util.Locale

internal fun shouldRunFilesystemVideoCheck(
  forceFileSystemCheck: Boolean,
  mediaStoreResultCount: Int,
): Boolean = forceFileSystemCheck || mediaStoreResultCount == 0

internal fun shouldIncludePrimaryStorageInFilesystemFolderScan(
  options: MediaScanOptions,
  forceFileSystemCheck: Boolean,
): Boolean = forceFileSystemCheck || options.includeNoMediaFolders

internal fun getPrimaryStorageSupplementalScanRoots(primaryStorageRoot: File): List<File> {
  val normalizedRoot = normalizeStoragePath(primaryStorageRoot.absolutePath) ?: return emptyList()
  val candidates =
    listOf(
      File(primaryStorageRoot, "Android/data"),
      File(primaryStorageRoot, "Android/media"),
    )

  return candidates.filter { candidate ->
    val candidatePath = normalizeStoragePath(candidate.absolutePath) ?: return@filter false
    candidate.exists() &&
      candidate.canRead() &&
      candidate.isDirectory &&
      candidatePath.startsWith("$normalizedRoot/")
  }
}

internal fun isAndroidDataAccessiblePath(file: File?): Boolean {
  val normalizedPath =
    runCatching { storagePathKey(file?.absolutePath) }.getOrNull()
      ?: return false

  return normalizedPath.endsWith("/android") || normalizedPath.contains("/android/data")
}

internal fun normalizeStoragePath(path: String?): String? {
  val rawPath = path?.trim()?.replace('\\', '/') ?: return null
  if (rawPath.isBlank()) {
    return null
  }

  val collapsedSeparators = rawPath.replace(Regex("/+"), "/")
  val normalized =
    if (collapsedSeparators.length > 1) {
      collapsedSeparators.trimEnd('/')
    } else {
      collapsedSeparators
    }

  return normalized.ifBlank { "/" }
}

internal fun storagePathKey(path: String?): String? =
  normalizeStoragePath(path)?.lowercase(Locale.ROOT)

internal fun areEquivalentStoragePaths(
  first: String?,
  second: String?,
): Boolean {
  val firstKey = storagePathKey(first)
  val secondKey = storagePathKey(second)
  return firstKey != null && firstKey == secondKey
}

internal fun parentStoragePath(path: String?): String? {
  val normalizedPath = normalizeStoragePath(path) ?: return null
  if (normalizedPath == "/") {
    return null
  }

  val parentPath = normalizedPath.substringBeforeLast('/', "")
  return when {
    parentPath.isEmpty() -> "/"
    parentPath == normalizedPath -> null
    else -> parentPath
  }
}

internal fun leafStorageName(path: String?): String =
  normalizeStoragePath(path)
    ?.substringAfterLast('/')
    ?.takeIf { it.isNotBlank() }
    ?: ""

internal fun isStoragePathDescendant(
  parentPath: String?,
  candidatePath: String?,
): Boolean {
  val normalizedParent = normalizeStoragePath(parentPath) ?: return false
  val normalizedCandidate = normalizeStoragePath(candidatePath) ?: return false
  val parentKey = storagePathKey(normalizedParent) ?: return false
  val candidateKey = storagePathKey(normalizedCandidate) ?: return false

  return candidateKey != parentKey && candidateKey.startsWith("$parentKey/")
}

internal fun isDirectStorageChild(
  parentPath: String?,
  candidatePath: String?,
): Boolean {
  val parentKey = storagePathKey(parentPath) ?: return false
  val candidateKey = storagePathKey(candidatePath) ?: return false
  if (candidateKey == parentKey || !candidateKey.startsWith("$parentKey/")) {
    return false
  }

  val relativePath = candidateKey.removePrefix("$parentKey/")
  return !relativePath.contains('/')
}

internal fun choosePreferredStoragePath(
  existingPath: String,
  candidatePath: String,
): String {
  val normalizedExisting = normalizeStoragePath(existingPath) ?: existingPath
  val normalizedCandidate = normalizeStoragePath(candidatePath) ?: candidatePath

  if (areEquivalentStoragePaths(normalizedExisting, normalizedCandidate)) {
    val existingScore = storageDisplayScore(normalizedExisting)
    val candidateScore = storageDisplayScore(normalizedCandidate)
    return if (candidateScore > existingScore) normalizedCandidate else normalizedExisting
  }

  return normalizedExisting
}

private fun storageDisplayScore(path: String): Int {
  val uppercaseScore = path.count { it.isUpperCase() } * 2
  val mediaFolderBonus =
    listOf("DCIM", "Movies", "Pictures", "Download", "Camera")
      .count { preferredName -> path.contains("/$preferredName") || path.endsWith(preferredName) }
  return uppercaseScore + mediaFolderBonus
}

