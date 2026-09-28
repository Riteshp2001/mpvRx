/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.preferences.components

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.runtime.OptionalRuntimePack
import app.gyrolet.mpvrx.runtime.OptionalRuntimePackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun RuntimePackPreference(
  pack: OptionalRuntimePack,
  @StringRes titleRes: Int,
  @StringRes summaryRes: Int,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val installed by
    remember(pack) { OptionalRuntimePackManager.observeInstalled(context, pack) }
      .collectAsState(initial = OptionalRuntimePackManager.isInstalled(context, pack))
  val progressFlow = remember(pack) { MutableStateFlow(0f) }
  val progress by progressFlow.collectAsState()
  var downloading by remember(pack) { mutableStateOf(false) }

  SwitchPreference(
    value = installed,
    enabled = !downloading,
    onValueChange = { enabled ->
      if (enabled) {
        progressFlow.value = 0f
        downloading = true
        scope.launch {
          val result =
            OptionalRuntimePackManager.downloadAndRequestInstall(context, pack) {
              progressFlow.value = it
            }
          downloading = false
          result.exceptionOrNull()?.let { error ->
            val message =
              if (error is OptionalRuntimePackManager.InstallPermissionRequiredException) {
                context.getString(R.string.runtime_pack_install_permission_required)
              } else {
                context.getString(R.string.runtime_pack_install_failed)
              }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
          }
        }
      } else {
        OptionalRuntimePackManager.requestUninstall(context, pack)
      }
    },
    title = { Text(stringResource(titleRes)) },
    summary = {
      Text(
        when {
          downloading -> stringResource(R.string.runtime_pack_downloading, (progress * 100).toInt())
          installed -> stringResource(R.string.runtime_pack_installed)
          else -> stringResource(summaryRes)
        },
      )
    },
    modifier = modifier,
  )
}
