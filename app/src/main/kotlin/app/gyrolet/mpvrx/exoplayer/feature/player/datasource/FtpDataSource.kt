package app.gyrolet.mpvrx.exoplayer.feature.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import app.gyrolet.mpvrx.ui.browser.networkstreaming.clients.FtpClient
import org.apache.commons.net.ftp.FTPClient

@OptIn(UnstableApi::class)
class FtpDataSource private constructor(
    private val username: String,
    private val password: String,
) : BaseDataSource(true) {

    private var client: FTPClient? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var hasStartedTransfer: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val host = dataSpec.uri.host ?: throw IOException("FTP URI missing host")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: 21
        val path = dataSpec.uri.path?.ifBlank { null } ?: throw IOException("FTP URI missing path")

        val ftpClient = FTPClient().apply {
            connectTimeout = 10000
            dataTimeout = java.time.Duration.ofMillis(30000)
            setControlEncoding(Charsets.UTF_8.name())
            setAutodetectUTF8(true)
        }
        client = ftpClient
        try {
            ftpClient.connect(host, port)
            val loginOk = if (username.isBlank()) {
                ftpClient.login("anonymous", "")
            } else {
                ftpClient.login(username, password)
            }
            if (!loginOk) throw IOException("FTP login failed")

            ftpClient.enterLocalPassiveMode()
            ftpClient.setFileType(FTPClient.BINARY_FILE_TYPE)
            val fileSize = ftpClient.mlistFile(path)?.size ?: C.LENGTH_UNSET.toLong()
            if (dataSpec.position > 0) {
                ftpClient.setRestartOffset(dataSpec.position)
            }

            val stream = ftpClient.retrieveFileStream(path) ?: throw IOException("FTP retrieve failed: $path")
            inputStream = stream
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                fileSize != C.LENGTH_UNSET.toLong() -> fileSize - dataSpec.position
                else -> C.LENGTH_UNSET.toLong()
            }

            transferStarted(dataSpec)
            hasStartedTransfer = true
            return bytesRemaining
        } catch (exception: IOException) {
            close()
            throw exception
        } catch (exception: RuntimeException) {
            close()
            throw exception
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val bytesRead = inputStream?.read(buffer, offset, bytesToRead)
            ?: return C.RESULT_END_OF_INPUT
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val shouldCompletePendingCommand = inputStream != null
        runCatching { inputStream?.close() }
        if (shouldCompletePendingCommand) {
            runCatching { client?.completePendingCommand() }
        }
        runCatching {
            if (client?.isConnected == true) client?.logout()
        }
        runCatching {
            if (client?.isConnected == true) client?.disconnect()
        }
        inputStream = null
        client = null
        uri = null
        bytesRemaining = 0
        if (hasStartedTransfer) {
            hasStartedTransfer = false
            transferEnded()
        }
    }

    class Factory(
        private val username: String,
        private val password: String,
    ) : DataSource.Factory {
        override fun createDataSource(): FtpDataSource = FtpDataSource(username, password)
    }
}
