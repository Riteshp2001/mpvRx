package app.gyrolet.mpvrx.exoplayer.feature.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class SmbDataSource private constructor(
    private val username: String,
    private val password: String,
) : BaseDataSource(true) {

    private var client: SMBClient? = null
    private var share: DiskShare? = null
    private var smbFile: com.hierynomus.smbj.share.File? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var hasStartedTransfer: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val host = dataSpec.uri.host ?: throw IOException("SMB URI missing host")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: DEFAULT_PORT
        val pathSegments = dataSpec.uri.pathSegments
        if (pathSegments.size < 2) throw IOException("SMB URI path too short: ${dataSpec.uri}")

        val shareName = pathSegments.first()
        val filePath = pathSegments.drop(1).joinToString("\\")

        val config = SmbConfig.builder()
            .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val smbClient = SMBClient(config)
        client = smbClient

        val connection = smbClient.connect(host, port)
        val authContext = toAuthenticationContext(username, password)
        val session = connection.authenticate(authContext)
        val diskShare = session.connectShare(shareName) as DiskShare
        share = diskShare

        val file = diskShare.openFile(
            filePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(com.hierynomus.mssmb2.SMB2CreateOptions::class.java),
        )
        smbFile = file

        val fileSize = file.fileInformation.standardInformation.endOfFile
        val stream = file.inputStream

        if (dataSpec.position > 0) {
            var remaining = dataSpec.position
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }
        inputStream = stream

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            fileSize - dataSpec.position
        }

        transferStarted(dataSpec)
        hasStartedTransfer = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytesRead = inputStream?.read(buffer, offset, bytesToRead)
            ?: return C.RESULT_END_OF_INPUT
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            smbFile?.close()
        } catch (_: Exception) {
        }
        try {
            share?.close()
        } catch (_: Exception) {
        }
        try {
            client?.close()
        } catch (_: Exception) {
        }
        inputStream = null
        smbFile = null
        share = null
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
        override fun createDataSource(): SmbDataSource = SmbDataSource(username, password)
    }

    companion object {
        private const val DEFAULT_PORT = 445
        private const val TIMEOUT_SECONDS = 30L

        private fun toAuthenticationContext(username: String, password: String): AuthenticationContext {
            if (username.isBlank()) return AuthenticationContext.anonymous()

            val domain = username.substringBefore('\\', missingDelimiterValue = "")
                .substringBefore('/', missingDelimiterValue = "")
            val account = username.substringAfterLast('\\').substringAfterLast('/')

            return AuthenticationContext(account, password.toCharArray(), domain)
        }
    }
}
