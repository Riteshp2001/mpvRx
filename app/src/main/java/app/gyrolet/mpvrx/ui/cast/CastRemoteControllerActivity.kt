/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
