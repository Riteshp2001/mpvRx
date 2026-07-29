/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.cast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme

class CastRemoteControllerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val controller = CastPlaybackController.instance
    if (controller == null) {
      finish()
      return
    }

    setContent {
      MpvrxTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          val castState by controller.castState.collectAsState()

          CastRemoteControllerScreen(
            castState = castState,
            controller = controller,
            onBackClick = { finish() },
            onStopCasting = {
              controller.disconnect()
              finish()
            },
          )
        }
      }
    }
  }
}
