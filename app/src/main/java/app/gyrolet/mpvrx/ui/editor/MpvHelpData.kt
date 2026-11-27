/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * The documentation catalog is parsed from the official mpv manual, not curated by hand.
 */
package app.gyrolet.mpvrx.ui.editor

import kotlinx.serialization.Serializable

@Serializable
enum class HelpEntryKind(
  val label: String,
) {
  OPTION("mpv.conf Option"),
  COMMAND("Input Command"),
  PROPERTY("Property"),
  JS_API("JavaScript API"),
}

@Serializable
data class HelpEntry(
  val name: String,
  val kind: HelpEntryKind,
  val category: String,
  val signature: String,
  val description: String,
  val androidCompatible: Boolean = true,
  val androidNote: String? = null,
)

data class HelpCategory(
  val name: String,
  val entries: List<HelpEntry>,
)
