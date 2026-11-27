// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val REMOTE_PROTOCOL_VERSION = 1
internal const val REMOTE_SERVICE_TYPE = "_mpvrxremote._tcp"
internal const val REMOTE_DEFAULT_PORT = 47821
internal const val REMOTE_NONCE_BYTES = 32
internal const val REMOTE_SECRET_BYTES = 32
internal const val REMOTE_MAX_TEXT_LENGTH = 4_096
private const val MAX_FRAME_BYTES = 64 * 1024

/** One flat envelope keeps the wire format small; each message type uses a subset of the fields. */
@Serializable
internal data class RemoteMessage(
  val type: String,
  val version: Int? = null,
  val publicKey: String? = null,
  val commitment: String? = null,
  val nonce: String? = null,
  val device: String? = null,
  val pairId: String? = null,
  val tvId: String? = null,
  val name: String? = null,
  val mode: String? = null,
  val proof: String? = null,
  val secret: String? = null,
  val key: String? = null,
  val text: String? = null,
  val ok: Boolean? = null,
  val reason: String? = null,
)

internal object RemoteMessageType {
  const val HELLO = "hello"
  const val NONCE = "nonce"
  const val AUTH = "auth"
  const val PAIRED = "paired"
  const val ERROR = "error"
  const val KEY = "key"
  const val TEXT = "text"
  const val ACK = "ack"
}

internal object RemoteMode {
  const val RESUME = "resume"
  const val PAIR = "pair"
}

internal object RemoteReason {
  const val DENIED = "denied"
  const val BUSY = "busy"
  const val UNPAIRED = "unpaired"
  const val BACKGROUND = "background"
  const val UNSUPPORTED = "unsupported"
}

internal object RemoteKey {
  const val UP = "up"
  const val DOWN = "down"
  const val LEFT = "left"
  const val RIGHT = "right"
  const val CENTER = "center"
  const val BACK = "back"
  const val MENU = "menu"
  const val PLAY_PAUSE = "play_pause"
  const val REWIND = "rewind"
  const val FAST_FORWARD = "fast_forward"
  const val VOLUME_UP = "volume_up"
  const val VOLUME_DOWN = "volume_down"
  const val BACKSPACE = "backspace"
  const val ENTER = "enter"
  const val CLEAR_FIELD = "clear_field"
}

private val remoteJson = Json { ignoreUnknownKeys = true }

internal fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

internal fun String?.fromBase64(expectedSize: Int? = null): ByteArray {
  val decoded = Base64.getDecoder().decode(requireNotNull(this) { "Missing remote field" })
  require(expectedSize == null || decoded.size == expectedSize) { "Unexpected remote field size" }
  return decoded
}

/** AES-256-GCM per direction; counters are the nonces, so a replayed or reordered frame fails to open. */
internal class RemoteCipher(
  sendKeyBytes: ByteArray,
  receiveKeyBytes: ByteArray,
) {
  private val sendKey = SecretKeySpec(sendKeyBytes, "AES")
  private val receiveKey = SecretKeySpec(receiveKeyBytes, "AES")
  private var sent = 0L
  private var received = 0L

  fun seal(plain: ByteArray): ByteArray = transform(Cipher.ENCRYPT_MODE, sendKey, sent++, plain)

  fun open(sealed: ByteArray): ByteArray = transform(Cipher.DECRYPT_MODE, receiveKey, received++, sealed)

  private fun transform(
    mode: Int,
    key: SecretKeySpec,
    counter: Long,
    input: ByteArray,
  ): ByteArray {
    val iv = ByteArray(12)
    for (index in 0 until 8) iv[4 + index] = (counter ushr (56 - index * 8)).toByte()
    return Cipher.getInstance("AES/GCM/NoPadding").run {
      init(mode, key, GCMParameterSpec(128, iv))
      doFinal(input)
    }
  }
}

/** Length-prefixed JSON frames, sealed once the handshake has produced a [cipher]. */
internal class RemoteChannel(
  private val socket: Socket,
) : Closeable {
  private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
  private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
  private val writeLock = Any()

  @Volatile var cipher: RemoteCipher? = null

  fun send(message: RemoteMessage) {
    val plain = remoteJson.encodeToString(RemoteMessage.serializer(), message).encodeToByteArray()
    synchronized(writeLock) {
      val payload = cipher?.seal(plain) ?: plain
      output.writeInt(payload.size)
      output.write(payload)
      output.flush()
    }
  }

  fun receive(): RemoteMessage {
    val size = input.readInt()
    require(size in 1..MAX_FRAME_BYTES) { "Invalid remote frame" }
    val payload = ByteArray(size)
    input.readFully(payload)
    val plain = cipher?.open(payload) ?: payload
    return remoteJson.decodeFromString(RemoteMessage.serializer(), plain.decodeToString())
  }

  override fun close() = socket.close()
}

/**
 * Ephemeral ECDH per connection. A first pairing is authenticated by a six-digit code derived from
 * a committed nonce exchange, which the TV user compares before approving; later connections prove
 * the stored pairing secret with HMACs, and every session key also depends on it.
 */
internal object RemoteCrypto {
  private val random = SecureRandom()
  private val label = "mpvrx-remote-v1".encodeToByteArray()

  fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

  fun generateKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").run {
      initialize(ECGenParameterSpec("secp256r1"), random)
      generateKeyPair()
    }

  fun decodePublicKey(encoded: ByteArray): PublicKey =
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

  fun sharedSecret(
    keyPair: KeyPair,
    peer: PublicKey,
  ): ByteArray =
    KeyAgreement.getInstance("ECDH").run {
      init(keyPair.private)
      doPhase(peer, true)
      generateSecret()
    }

  fun commitment(
    serverKey: ByteArray,
    clientKey: ByteArray,
    serverNonce: ByteArray,
  ): ByteArray = sha256(label, "commit".encodeToByteArray(), serverKey, clientKey, serverNonce)

  fun transcript(
    clientKey: ByteArray,
    serverKey: ByteArray,
    clientNonce: ByteArray,
    serverNonce: ByteArray,
    tvId: String,
  ): ByteArray =
    sha256(
      label,
      "transcript".encodeToByteArray(),
      clientKey,
      serverKey,
      clientNonce,
      serverNonce,
      tvId.encodeToByteArray(),
    )

  fun pairingCode(transcript: ByteArray): String {
    val digest = sha256(label, "code".encodeToByteArray(), transcript)
    val value =
      ((digest[0].toLong() and 0xff) shl 24) or
        ((digest[1].toLong() and 0xff) shl 16) or
        ((digest[2].toLong() and 0xff) shl 8) or
        (digest[3].toLong() and 0xff)
    return (value % 1_000_000L).toString().padStart(6, '0')
  }

  fun proof(
    secret: ByteArray,
    role: String,
    transcript: ByteArray,
  ): ByteArray = hmac(secret, label, role.encodeToByteArray(), transcript)

  fun sessionCipher(
    shared: ByteArray,
    secret: ByteArray?,
    transcript: ByteArray,
    server: Boolean,
  ): RemoteCipher {
    val keys = hkdf(shared + (secret ?: ByteArray(0)), transcript, label, 64)
    val clientToServer = keys.copyOfRange(0, 32)
    val serverToClient = keys.copyOfRange(32, 64)
    return if (server) RemoteCipher(serverToClient, clientToServer) else RemoteCipher(clientToServer, serverToClient)
  }

  fun matches(
    expected: ByteArray,
    actual: ByteArray,
  ): Boolean = MessageDigest.isEqual(expected, actual)

  private fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
      parts.forEach { update(it) }
      digest()
    }

  private fun hmac(
    key: ByteArray,
    vararg parts: ByteArray,
  ): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key, "HmacSHA256"))
      parts.forEach { update(it) }
      doFinal()
    }

  /** RFC 5869 HKDF-SHA256. */
  private fun hkdf(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
  ): ByteArray {
    val prk = hmac(salt, ikm)
    val output = ByteArray(length)
    var block = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
      block = hmac(prk, block, info, byteArrayOf(counter.toByte()))
      val count = minOf(block.size, length - offset)
      block.copyInto(output, offset, 0, count)
      offset += count
      counter++
    }
    return output
  }
}
