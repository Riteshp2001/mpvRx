package app.gyrolet.mpvrx.exoplayer.core.model

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val contentType: String = "",
)

