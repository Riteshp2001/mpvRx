package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

class OpenDocumentWithInitialUri : ActivityResultContract<OpenDocumentWithInitialUri.Input, Uri?>() {

    data class Input(
        val mimeTypes: Array<String>,
        val initialUri: Uri? = null,
    )

    override fun createIntent(context: Context, input: Input): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        type = if (input.mimeTypes.size == 1) input.mimeTypes.first() else "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, input.mimeTypes)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        input.initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}
