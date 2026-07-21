package app.gyrolet.mpvrx.domain.syncplay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class SyncplayClient {
    private val TAG = "SyncplayClient"
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val writeMutex = Mutex()

    private val _messages = MutableSharedFlow<SyncplayMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<SyncplayMessage> = _messages.asSharedFlow()

    suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val newSocket = Socket()
        try {
            socket = newSocket
            newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            newSocket.keepAlive = true
            newSocket.soTimeout = 0
            reader = BufferedReader(InputStreamReader(newSocket.inputStream, StandardCharsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(newSocket.outputStream, StandardCharsets.UTF_8))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
            false
        }
    }

    suspend fun listen() = withContext(Dispatchers.IO) {
        val listeningSocket = socket
        val listeningReader = reader
        try {
            while (true) {
                val line = listeningReader?.readLine() ?: break
                if (line.isBlank()) continue

                try {
                    val message = json.decodeFromString<SyncplayMessage>(line)
                    _messages.emit(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $line", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listen error", e)
        } finally {
            if (socket === listeningSocket) {
                disconnect()
            }
        }
    }

    suspend fun sendMessage(message: SyncplayMessage): Boolean {
        return sendRawMessage(json.encodeToString(message))
    }

    suspend fun sendRawMessage(json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            writeMutex.withLock {
                val activeWriter = writer ?: return@withLock false
                activeWriter.write(json)
                activeWriter.write("\r\n")
                activeWriter.flush()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            disconnect()
            false
        }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
        socket = null
        reader = null
        writer = null
    }

    fun isConnected(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
    }
}
