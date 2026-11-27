// SPDX-License-Identifier: AGPL-3.0-or-later

package app.gyrolet.mpvrx.data.network.client

import java.io.IOException

/** Android replacement for SMBJ-RPC's java.rmi.UnmarshalException, remapped during packaging. */
class SmbRpcDecodeException : IOException {
  constructor(message: String) : super(message)

  constructor(message: String, cause: Exception) : super(message, cause)
}
