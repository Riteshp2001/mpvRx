package app.gyrolet.mpvrx.ui.player

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val clockFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

actual fun localClockText(epochMillis: Long): String =
  clockFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
