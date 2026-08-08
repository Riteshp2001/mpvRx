/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import app.gyrolet.mpvrx.data.network.client.NetworkClient
import app.gyrolet.mpvrx.data.network.client.NetworkClientFactory
import app.gyrolet.mpvrx.data.network.credentials.NetworkCredentialCipher
import app.gyrolet.mpvrx.data.network.credentials.NetworkCredentialStorageException
import app.gyrolet.mpvrx.data.network.credentials.NetworkCredentialUnavailableException
import app.gyrolet.mpvrx.database.dao.NetworkConnectionDao
import app.gyrolet.mpvrx.domain.network.ConnectionStatus
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Manages saved network connections and the clients currently using them. */
class NetworkRepository(
  private val dao: NetworkConnectionDao,
  private val credentialCipher: NetworkCredentialCipher,
  private val clientFactory: (NetworkConnection) -> NetworkClient = NetworkClientFactory::createClient,
) {
  private val activeClients = ConcurrentHashMap<Long, NetworkClient>()

  // ponytail: network connections are uncommon, so one lifecycle lock is simpler and safer.
  // Replace it with per-connection locks only if parallel connection setup becomes measurable.
  private val clientLifecycleMutex = Mutex()
  private val credentialMutex = Mutex()

  private val _connectionStatuses = MutableStateFlow<Map<Long, ConnectionStatus>>(emptyMap())
  val connectionStatuses: StateFlow<Map<Long, ConnectionStatus>> = _connectionStatuses.asStateFlow()

  /** UI-facing connection models are always password-redacted. */
  fun getAllConnections(): Flow<List<NetworkConnection>> =
    dao
      .getAllConnections()
      .map { connections -> connections.map { redactAndMigrate(it) } }
      .flowOn(Dispatchers.IO)

  suspend fun getAutoConnectConnections(): List<NetworkConnection> =
    withContext(Dispatchers.IO) {
      dao.getAutoConnectConnections().map { redactAndMigrate(it) }
    }

  suspend fun getConnectionById(id: Long): NetworkConnection? =
    withContext(Dispatchers.IO) {
      dao.getConnectionById(id)?.let { redactAndMigrate(it) }
    }

  suspend fun addConnection(connection: NetworkConnection): Long =
    withContext(Dispatchers.IO) {
      credentialMutex.withLock {
        val password =
          if (connection.isAnonymous || connection.password.isEmpty()) {
            ""
          } else {
            encryptForStorage(connection.password)
          }
        dao.insert(connection.copy(password = password))
      }
    }

  /**
   * A blank password keeps the stored password. Set [clearPassword] for an intentional removal.
   */
  suspend fun updateConnection(
    connection: NetworkConnection,
    clearPassword: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    clientLifecycleMutex.withLock {
      var reconnectRequired = false
      credentialMutex.withLock {
        val existing = dao.getConnectionById(connection.id)
        val credentialChanged =
          when {
            connection.isAnonymous || clearPassword -> existing?.password?.isNotEmpty() == true
            connection.password.isNotEmpty() -> true
            else -> false
          }
        val storedPassword =
          when {
            connection.isAnonymous || clearPassword -> ""
            connection.password.isNotEmpty() -> encryptForStorage(connection.password)
            existing == null -> ""
            existing.password.isEmpty() -> ""
            credentialCipher.isEncrypted(existing.password) -> existing.password
            else -> migratePlaintextPasswordLocked(existing)
          }

        val updated = connection.copy(password = storedPassword)
        reconnectRequired =
          existing?.hasSameConnectionSettings(updated) != true || credentialChanged
        dao.update(updated)
      }
      if (reconnectRequired) {
        val oldClient = activeClients.remove(connection.id)
        val closeError = oldClient?.let { closeClient(it) }
        updateConnectionStatus(
          connection.id,
          ConnectionStatus(
            connectionId = connection.id,
            error = closeError?.message,
          ),
        )
      }
      coroutineContext.ensureActive()
    }
  }

  suspend fun deleteConnection(connection: NetworkConnection) =
    withContext(Dispatchers.IO) {
      clientLifecycleMutex.withLock {
        dao.deleteById(connection.id)
        val oldClient = activeClients.remove(connection.id)
        _connectionStatuses.update { it - connection.id }
        oldClient?.let { closeClient(it) }
        coroutineContext.ensureActive()
      }
    }

  /** Returns a new, unconnected client whose credentials are resolved only in memory. */
  suspend fun createClient(connectionId: Long): Result<NetworkClient> =
    withContext(Dispatchers.IO) {
      try {
        val stored =
          dao.getConnectionById(connectionId)
            ?: throw IllegalArgumentException("Network connection not found")
        Result.success(clientFactory(resolveCredential(stored)))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

  suspend fun connect(connection: NetworkConnection): Result<Unit> =
    withContext(Dispatchers.IO) {
      clientLifecycleMutex.withLock {
        updateConnectionStatus(
          connection.id,
          ConnectionStatus(connectionId = connection.id, isConnecting = true),
        )

        var candidate: NetworkClient? = null
        try {
          val stored = dao.getConnectionById(connection.id) ?: connection
          val newClient = clientFactory(resolveCredential(stored))
          candidate = newClient

          activeClients.remove(connection.id)?.let { closeClient(it) }
          newClient.connect().getOrThrow()

          try {
            dao.updateLastConnected(connection.id, System.currentTimeMillis())
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            // A bookkeeping failure must not discard a successfully opened connection.
          }

          activeClients[connection.id] = newClient
          candidate = null
          updateConnectionStatus(
            connection.id,
            ConnectionStatus(connectionId = connection.id, isConnected = true),
          )
          Result.success(Unit)
        } catch (e: CancellationException) {
          candidate?.let { closeClient(it) }
          restoreIdleStatus(connection.id)
          throw e
        } catch (e: Exception) {
          candidate?.let { closeClient(it) }
          updateConnectionStatus(
            connection.id,
            ConnectionStatus(
              connectionId = connection.id,
              isConnected = hasConnectedClient(connection.id),
              error = e.safeMessage(),
            ),
          )
          Result.failure(e)
        }
      }
    }

  suspend fun disconnect(connection: NetworkConnection): Result<Unit> =
    withContext(Dispatchers.IO) {
      clientLifecycleMutex.withLock {
        val client = activeClients.remove(connection.id)
        val closeError = client?.let { closeClient(it) }
        updateConnectionStatus(
          connection.id,
          ConnectionStatus(connectionId = connection.id, error = closeError?.message),
        )
        coroutineContext.ensureActive()
        closeError?.let { Result.failure(it) } ?: Result.success(Unit)
      }
    }

  suspend fun listFiles(
    connection: NetworkConnection,
    path: String,
  ): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      clientLifecycleMutex.withLock {
        try {
          val stored = dao.getConnectionById(connection.id) ?: connection
          val resolved = resolveCredential(stored)
          var client = activeClients[connection.id]

          if (client == null || !client.isConnected()) {
            activeClients.remove(connection.id)?.let { closeClient(it) }
            val candidate = clientFactory(resolved)
            try {
              candidate.connect().getOrThrow()
              activeClients[connection.id] = candidate
              client = candidate
              updateConnectionStatus(
                connection.id,
                ConnectionStatus(connectionId = connection.id, isConnected = true),
              )
            } catch (e: CancellationException) {
              closeClient(candidate)
              throw e
            } catch (e: Exception) {
              closeClient(candidate)
              throw e
            }
          }

          val readyClient = checkNotNull(client)
          readyClient.listFiles(path).also { result ->
            result.exceptionOrNull()?.let { error ->
              if (error is CancellationException) throw error
              val stillConnected = readyClient.isConnected()
              if (!stillConnected) {
                activeClients.remove(connection.id, readyClient)
                closeClient(readyClient)
              }
              updateConnectionStatus(
                connection.id,
                ConnectionStatus(
                  connectionId = connection.id,
                  isConnected = stillConnected,
                  error = error.safeMessage(),
                ),
              )
            }
          }
        } catch (e: CancellationException) {
          restoreIdleStatus(connection.id)
          throw e
        } catch (e: Exception) {
          updateConnectionStatus(
            connection.id,
            ConnectionStatus(
              connectionId = connection.id,
              isConnected = hasConnectedClient(connection.id),
              error = e.safeMessage(),
            ),
          )
          Result.failure(e)
        }
      }
    }

  fun getActiveClient(connectionId: Long): NetworkClient? = activeClients[connectionId]

  fun isConnected(connectionId: Long): Boolean = hasConnectedClient(connectionId)

  suspend fun disconnectAll() =
    withContext(Dispatchers.IO) {
      clientLifecycleMutex.withLock {
        val clients = activeClients.values.toList()
        activeClients.clear()
        _connectionStatuses.value = emptyMap()
        clients.forEach { closeClient(it) }
        coroutineContext.ensureActive()
      }
    }

  private suspend fun redactAndMigrate(connection: NetworkConnection): NetworkConnection {
    return credentialMutex.withLock {
      val latest = dao.getConnectionById(connection.id) ?: connection
      if (latest.password.isNotEmpty() && !credentialCipher.isEncrypted(latest.password)) {
        try {
          migratePlaintextPasswordLocked(latest)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Keep the original row intact and retry on the next read/use.
        }
      }
      latest.copy(password = "")
    }
  }

  private suspend fun resolveCredential(connection: NetworkConnection): NetworkConnection =
    credentialMutex.withLock {
      val latest = dao.getConnectionById(connection.id) ?: connection
      val password =
        when {
          latest.password.isEmpty() -> ""
          !credentialCipher.isEncrypted(latest.password) -> {
            migratePlaintextPasswordLocked(latest)
            latest.password
          }
          else ->
            try {
              credentialCipher.decrypt(latest.password)
            } catch (e: Exception) {
              throw NetworkCredentialUnavailableException(e)
            }
        }
      latest.copy(password = password)
    }

  private suspend fun migratePlaintextPasswordLocked(connection: NetworkConnection): String {
    val encrypted = encryptForStorage(connection.password)
    dao.updateEncryptedPassword(connection.id, encrypted)
    return encrypted
  }

  private fun encryptForStorage(password: String): String =
    try {
      credentialCipher.encrypt(password)
    } catch (e: Exception) {
      throw NetworkCredentialStorageException(e)
    }

  private suspend fun closeClient(client: NetworkClient): Exception? =
    withContext(NonCancellable) {
      try {
        client.disconnect()
        null
      } catch (e: Exception) {
        e
      }
    }

  private fun restoreIdleStatus(connectionId: Long) {
    updateConnectionStatus(
      connectionId,
      ConnectionStatus(
        connectionId = connectionId,
        isConnected = hasConnectedClient(connectionId),
      ),
    )
  }

  private fun updateConnectionStatus(
    connectionId: Long,
    status: ConnectionStatus,
  ) {
    _connectionStatuses.update { it + (connectionId to status) }
  }

  private fun hasConnectedClient(connectionId: Long): Boolean =
    activeClients[connectionId]?.isConnected() == true

  private fun NetworkConnection.hasSameConnectionSettings(other: NetworkConnection): Boolean =
    protocol == other.protocol &&
      host == other.host &&
      port == other.port &&
      username == other.username &&
      path == other.path &&
      isAnonymous == other.isAnonymous &&
      useHttps == other.useHttps

  private fun Throwable.safeMessage(): String =
    if (
      this is NetworkCredentialUnavailableException ||
      this is NetworkCredentialStorageException
    ) {
      message.orEmpty()
    } else {
      message ?: "Connection failed"
    }
}
