/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.data.network.client

import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.PipeShare
import com.rapid7.client.dcerpc.Interface
import com.rapid7.client.dcerpc.RPCException
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.RPCTransport
import java.io.IOException
import java.util.EnumSet

/** Enumerates disk shares through SRVSVC on the already-authenticated SMB2/3 session. */
internal object SmbShareDiscovery {
  fun listShares(session: Session): List<NetworkFile> {
    val ipc =
      session.connectShare("IPC$") as? PipeShare
        ?: throw IOException("SMB server does not support shared-folder browsing")
    // Use SMBJ's public pipe API, not RPC's older low-level SMB packet helpers. Close only
    // this pipe handle; IPC$ and any disk trees are cached and owned by the session.
    return ipc
      .open(
        "srvsvc",
        SMB2ImpersonationLevel.Impersonation,
        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
        null,
        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
        SMB2CreateDisposition.FILE_OPEN,
        null,
      ).use { pipe ->
        val transport =
          object : RPCTransport() {
            override fun transact(
              packetOut: ByteArray,
              packetIn: ByteArray,
            ): Int = pipe.transact(packetOut, packetIn)

            override fun read(packetIn: ByteArray): Int = pipe.read(packetIn)

            override fun write(packetOut: ByteArray) {
              if (pipe.write(packetOut) != packetOut.size) throw IOException("Incomplete SMB RPC write")
            }
          }
        transport.bind(Interface.SRVSVC_V3_0, Interface.NDR_32BIT_V2)
        val shares =
          try {
            // Level 1 supplies names/types without requiring the administrative level-2 fields.
            // ServerService follows resume handles when the server returns multiple pages.
            ServerService(transport).shares1
          } catch (error: RPCException) {
            if (error.returnValue == 5) {
              throw NetworkAuthenticationException(
                "This account cannot list SMB shares. Sign in with an allowed account or enter a share path such as /Media.",
                error,
              )
            }
            throw error
          }
        shares
          .mapNotNull { share ->
            // SRVSVC includes Windows administrative shares (ADMIN$, C$, D$, ...).
            // STYPE_SPECIAL is the high bit of SHARE_INFO_1.type; the low bits
            // distinguish disk shares from IPC, printers and devices. Show only
            // ordinary disk shares at the server root. Hidden shares are still
            // accessible when the user explicitly configures /Share$ as a path.
            if (share.type and 0xFFFF != 0 ||
              // STYPE_SPECIAL | STYPE_TEMPORARY: exclude system and temporary exports.
              share.type and 0xC0000000.toInt() != 0
            ) {
              return@mapNotNull null
            }
            val name = share.netName ?: return@mapNotNull null
            if (name.endsWith("$")) return@mapNotNull null
            val path = runCatching { NetworkPath.ROOT.child(name) }.getOrNull() ?: return@mapNotNull null

            // Enumeration does not prove the current account can browse a share.
            // Some servers report administrative volumes as plain names (Admin, C,
            // E), even though they cannot be opened. Check only list permission,
            // not the directory contents: legitimately empty shares stay visible.
            // Leave the session-cached DiskShare alive; close just the probe handle.
            val browsable =
              try {
                val diskShare =
                  session.connectShare(name) as? com.hierynomus.smbj.share.DiskShare
                    ?: return@mapNotNull null
                diskShare
                  .openDirectory(
                    "",
                    EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
                    null,
                    EnumSet.allOf(SMB2ShareAccess::class.java),
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                  ).use { }
                true
              } catch (_: com.hierynomus.mssmb2.SMBApiException) {
                false // Invalid share or insufficient browse permissions.
              }
            if (!browsable) return@mapNotNull null
            NetworkFile(name = name, path = path.value, size = 0L, isDirectory = true)
          }.distinctBy { it.path.lowercase(java.util.Locale.ROOT) }
      }
  }
}
