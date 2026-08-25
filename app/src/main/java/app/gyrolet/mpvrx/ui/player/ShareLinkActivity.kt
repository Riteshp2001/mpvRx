/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.regex.Pattern

/**
 * Web-share entry point following Seal Plus' QuickDownloadActivity contract.
 * ACTION_VIEW keeps the supplied URL intact; ACTION_SEND extracts the first HTTP(S) URL from
 * surrounding share text before handing the URL to the player.
 */
class ShareLinkActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    forward(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    forward(intent)
  }

  private fun forward(source: Intent) {
    val sharedUrl = source.getSharedUrl()
    if (sharedUrl.isNullOrBlank()) {
      finish()
      return
    }

    startActivity(
      Intent(this, PlayerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(sharedUrl)
        source.extras?.let { extras -> putExtras(extras) }
        removeExtra(Intent.EXTRA_TEXT)
        removeExtra(Intent.EXTRA_STREAM)
        flags = source.flags and URI_PERMISSION_FLAGS
      },
    )
    finish()
  }

  private fun Intent.getSharedUrl(): String? =
    when (action) {
      Intent.ACTION_VIEW -> dataString
      Intent.ACTION_SEND ->
        getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
          removeExtra(Intent.EXTRA_TEXT)
          matchUrlFromSharedText(sharedText)
        }
      else -> null
    }

  private fun matchUrlFromSharedText(input: String): String? {
    val matcher = URL_PATTERN.matcher(input)
    return if (matcher.find()) matcher.group() else null
  }

  companion object {
    // Keep shared-text matching aligned with Seal Plus' TextUtil URL matcher.
    private const val URL_REGEX =
      "(http|https)://[\\w\\-_]+(\\.[\\w\\-_]+)+([\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])?"
    private val URL_PATTERN: Pattern = Pattern.compile(URL_REGEX)

    private const val URI_PERMISSION_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
  }
}
