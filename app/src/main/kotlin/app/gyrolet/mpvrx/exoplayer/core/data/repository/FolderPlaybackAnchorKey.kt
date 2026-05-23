package app.gyrolet.mpvrx.exoplayer.core.data.repository

import android.net.Uri

private const val REMOTE_FOLDER_PLAYBACK_ANCHOR_PREFIX = "mpvrx://folder"
private val SUPPORTED_REMOTE_PROTOCOLS = setOf("webdav", "ftp")

fun buildRemoteFolderPlaybackAnchorKey(
    remoteProtocol: String?,
    remoteServerId: Long?,
    directoryPath: String?,
): String? {
    val protocol = remoteProtocol?.lowercase()?.takeIf { it in SUPPORTED_REMOTE_PROTOCOLS } ?: return null
    val serverId = remoteServerId?.takeIf { it > 0L } ?: return null
    val normalizedPath = directoryPath?.normalizeFolderPlaybackAnchorPath() ?: return null
    return "$REMOTE_FOLDER_PLAYBACK_ANCHOR_PREFIX/$protocol/$serverId$normalizedPath"
}

fun String.normalizeFolderPlaybackAnchorPath(): String? {
    val raw = trim()
    if (raw.isBlank()) return null
    val decoded = runCatching { Uri.decode(raw) }.getOrDefault(raw)
    val normalized = decoded
        .replace(Regex("/+"), "/")
        .removeSuffix("/")
        .ifBlank { "/" }
    return if (normalized.startsWith('/')) normalized else "/$normalized"
}
