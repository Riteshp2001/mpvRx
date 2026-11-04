package app.gyrolet.mpvrx.ui.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gyrolet.mpvrx.ui.browser.dialogs.matchesPickerMimeType
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvDocumentsProviderTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test fun documentUriCanBeReadAndQueried() = withFiles { root ->
    val file = File(root, "playlist.m3u").apply { writeText("playlist contents") }
    val uri = TvDocumentUris.documentUri(context, file)

    assertTrue(DocumentsContract.isDocumentUri(context, uri))
    val contents = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    assertEquals("playlist contents", contents)
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    context.contentResolver.query(uri, columns, null, null, null)!!.use {
      assertTrue(it.moveToFirst())
      assertEquals("playlist.m3u", it.getString(0))
      assertEquals(file.length(), it.getLong(1))
    }
  }

  @Test fun treeSupportsCreateRenameAndDelete() = withFiles { root ->
    val uri = TvDocumentUris.treeUri(context, root)
    val tree = checkNotNull(DocumentFile.fromTreeUri(context, uri))
    assertTrue(tree.isDirectory)
    val folder = checkNotNull(tree.createDirectory("Backup"))
    val file = checkNotNull(folder.createFile("text/plain", "settings.txt"))
    context.contentResolver.openOutputStream(file.uri, "wt")!!.bufferedWriter().use { it.write("settings") }
    assertTrue(file.renameTo("renamed.txt"))
    assertEquals("settings", context.contentResolver.openInputStream(file.uri)!!.bufferedReader().use { it.readText() })
    assertEquals(listOf("renamed.txt"), folder.listFiles().mapNotNull { it.name })
    assertTrue(file.delete())
    assertTrue(folder.delete())
  }

  @Test fun documentIdsCannotEscapeStorage() {
    val error = runCatching { TvDocumentUris.file(context, "primary:../../../../data/local/tmp") }.exceptionOrNull()
    assertTrue(error is FileNotFoundException)
  }

  @Test fun treeGrantCanBePersisted() = withFiles { root ->
    val uri = TvDocumentUris.treeUri(context, root)
    val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val flags = access or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    context.grantUriPermission(context.packageName, uri, flags)
    try {
      context.contentResolver.takePersistableUriPermission(uri, access)
      assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission })
    } finally {
      context.contentResolver.releasePersistableUriPermission(uri, access)
    }
  }

  @Test fun multipleFileResultRetainsAllUris() = withFiles { root ->
    val first = File(root, "first.mp3").apply { writeText("first") }
    val second = File(root, "second.mp3").apply { writeText("second") }
    val result = buildTvPickerResult(context, listOf(first, second), tree = false)
    val parsed = ActivityResultContracts.OpenMultipleDocuments().parseResult(Activity.RESULT_OK, result)
    val expected = listOf(TvDocumentUris.documentUri(context, first), TvDocumentUris.documentUri(context, second))
    assertEquals(expected, parsed)
    assertTrue(result.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    assertTrue(result.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
  }

  @Test fun mediaFiltersIncludeSupportedExtensions() {
    assertTrue(matchesPickerMimeType(File("movie.iso"), listOf("video/*")))
    assertTrue(matchesPickerMimeType(File("audiobook.m4b"), listOf("audio/*")))
  }

  @Test fun discImagesKeepTheirMediaType() = withFiles { root ->
    val file = File(root, "movie.iso").apply { writeText("test") }
    val type = context.contentResolver.getType(TvDocumentUris.documentUri(context, file))
    assertEquals("application/x-iso9660-image", type)
  }

  @Test fun fallbackOnlyHandlesTvPickerResults() {
    TvPickerActivity.SUPPORTED_ACTIONS.forEach { action ->
      assertTrue(shouldOfferTvPickerFallback(true, 1, Intent(action)))
      assertFalse(shouldOfferTvPickerFallback(false, 1, Intent(action)))
      assertFalse(shouldOfferTvPickerFallback(true, -1, Intent(action)))
    }
    assertFalse(shouldOfferTvPickerFallback(true, 1, Intent(Intent.ACTION_VIEW)))
    val explicit = Intent(Intent.ACTION_OPEN_DOCUMENT).setClass(context, TvPickerActivity::class.java)
    assertFalse(shouldOfferTvPickerFallback(true, 1, explicit))
  }

  private fun withFiles(block: (File) -> Unit) {
    val external = checkNotNull(context.getExternalFilesDir(null))
    val root = File(external, "tv-documents-test-${UUID.randomUUID()}")
    check(root.mkdirs())
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }
}