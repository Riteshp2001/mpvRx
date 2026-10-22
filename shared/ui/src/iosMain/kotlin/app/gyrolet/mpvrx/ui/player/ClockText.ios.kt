package app.gyrolet.mpvrx.ui.player

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

actual fun localClockText(epochMillis: Long): String {
  val formatter = NSDateFormatter()
  formatter.timeStyle = NSDateFormatterShortStyle
  formatter.dateStyle = NSDateFormatterNoStyle
  return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}
