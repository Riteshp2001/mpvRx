package app.gyrolet.mpvrx.exoplayer.core.data.repository

import android.net.Uri

private const val REMOTE_PLAYBACK_STATE_PREFIX = "onlyplayer://remote"
private val SUPPORTED_REMOTE_PROTOCOLS = setOf("webdav", "ftp")

fun buildRemotePlaybackStateKey(
    remoteProtocol: String?,
    remoteServerId: Long?,
    remoteFilePath: String?,
): String? {
    val protocol = remoteProtocol?.lowercase()?.takeIf { it in SUPPORTED_REMOTE_PROTOCOLS } ?: return null
    val serverId = remoteServerId?.takeIf { it > 0L } ?: return null
    val normalizedPath = remoteFilePath.normalizeRemotePlaybackPath() ?: return null
    return "$REMOTE_PLAYBACK_STATE_PREFIX/$protocol/$serverId$normalizedPath"
}

fun buildPlaybackStateCandidates(
    originalUri: String,
    remoteProtocol: String?,
    remoteServerId: Long?,
    remoteFilePath: String?,
): List<String> {
    val stableKey = buildRemotePlaybackStateKey(
        remoteProtocol = remoteProtocol,
        remoteServerId = remoteServerId,
        remoteFilePath = remoteFilePath,
    )
    return listOfNotNull(stableKey, originalUri).distinct()
}

fun String.isRemotePlaybackStateKey(): Boolean = startsWith("$REMOTE_PLAYBACK_STATE_PREFIX/")

private fun String?.normalizeRemotePlaybackPath(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    val decoded = runCatching { Uri.decode(raw) }.getOrDefault(raw)
    val normalized = decoded
        .replace(Regex("/+"), "/")
        .removeSuffix("/")
        .takeIf { it.isNotBlank() && it != "/" }
        ?: return null
    return if (normalized.startsWith('/')) normalized else "/$normalized"
}
