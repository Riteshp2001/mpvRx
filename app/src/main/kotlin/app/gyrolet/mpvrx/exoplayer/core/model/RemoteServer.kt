package app.gyrolet.mpvrx.exoplayer.core.model

data class RemoteServer(
    val id: Long = 0,
    val name: String,
    val protocol: ServerProtocol,
    val host: String,
    val port: Int? = null,
    val path: String = "/",
    val username: String = "",
    val password: String = "",
    val isProxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
)

enum class ServerProtocol {
    WEBDAV,
    SMB,
    FTP,
}

