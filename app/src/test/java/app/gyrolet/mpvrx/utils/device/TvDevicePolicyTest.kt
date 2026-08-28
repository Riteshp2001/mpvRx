package app.gyrolet.mpvrx.utils.device

import android.content.res.Configuration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDevicePolicyTest {
  @Test
  fun televisionUiModeIsDetected() {
    assertTrue(
      TvDevicePolicy.isTelevision(
        uiModeType = Configuration.UI_MODE_TYPE_TELEVISION,
        hasLeanback = false,
        hasLeanbackOnly = false,
        hasTelevisionHardware = false,
      ),
    )
  }

  @Test
  fun leanbackFeaturesCoverMisreportedUiMode() {
    assertTrue(TvDevicePolicy.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, true, false, false))
    assertTrue(TvDevicePolicy.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, false, true, false))
    assertTrue(TvDevicePolicy.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, false, false, true))
  }

  @Test
  fun phonesAndTabletsRemainNonTelevision() {
    assertFalse(TvDevicePolicy.isTelevision(Configuration.UI_MODE_TYPE_NORMAL, false, false, false))
    assertFalse(TvDevicePolicy.isTelevision(null, false, false, false))
  }
}
