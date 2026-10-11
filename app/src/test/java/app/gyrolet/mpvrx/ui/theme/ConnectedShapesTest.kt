/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.theme

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectedShapesTest {
  private val tokens = ConnectedShapeTokens(outerCorner = 30.dp, innerCorner = 6.dp)

  @Test
  fun `single item stays fully rounded`() {
    assertEquals(
      ConnectedCornerRadii(30.dp, 30.dp, 30.dp, 30.dp),
      connectedCornerRadii(0, 1, ConnectedLayout.Grid, columns = 2, tokens = tokens),
    )
  }

  @Test
  fun `two by two grid only rounds exterior corners`() {
    assertEquals(
      ConnectedCornerRadii(30.dp, 6.dp, 6.dp, 6.dp),
      connectedCornerRadii(0, 4, ConnectedLayout.Grid, columns = 2, tokens = tokens),
    )
    assertEquals(
      ConnectedCornerRadii(6.dp, 30.dp, 6.dp, 6.dp),
      connectedCornerRadii(1, 4, ConnectedLayout.Grid, columns = 2, tokens = tokens),
    )
    assertEquals(
      ConnectedCornerRadii(6.dp, 6.dp, 6.dp, 30.dp),
      connectedCornerRadii(2, 4, ConnectedLayout.Grid, columns = 2, tokens = tokens),
    )
    assertEquals(
      ConnectedCornerRadii(6.dp, 6.dp, 30.dp, 6.dp),
      connectedCornerRadii(3, 4, ConnectedLayout.Grid, columns = 2, tokens = tokens),
    )
  }

  @Test
  fun `incomplete final row closes its visible exterior`() {
    assertEquals(
      ConnectedCornerRadii(6.dp, 6.dp, 30.dp, 30.dp),
      connectedCornerRadii(4, 5, ConnectedLayout.Grid, columns = 2, tokens = tokens),
    )
  }

  @Test
  fun `horizontal start and end mirror physically in rtl`() {
    val leading = connectedCornerRadii(0, 3, ConnectedLayout.Horizontal, tokens = tokens)
    assertEquals(
      ConnectedCornerRadii(6.dp, 30.dp, 30.dp, 6.dp),
      leading.physical(LayoutDirection.Rtl),
    )
  }
}
