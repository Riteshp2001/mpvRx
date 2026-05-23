package app.gyrolet.mpvrx.exoplayer.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.externalSubtitleFontDir
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.externalSubtitleFontFile
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.externalSubtitleFontMetaFile
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.externalSubtitleFontTempFile
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.externalSubtitleFontTempMetaFile
import app.gyrolet.mpvrx.exoplayer.core.data.model.ExternalSubtitleFontMeta

class LocalSubtitleFontRepository(
    private val context: Context,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val subtitleFontFileValidator: SubtitleFontFileValidator,
) : SubtitleFontRepository {

    companion object {
        private const val TAG = "LocalSubtitleFontRepository"
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val writeMutex = Mutex()
    private val stateInternal = MutableStateFlow(ExternalSubtitleFontState())
    private val sourceInternal = MutableStateFlow<ExternalSubtitleFontSource?>(null)

    override val state: StateFlow<ExternalSubtitleFontState> = stateInternal.asStateFlow()
    override val source: StateFlow<ExternalSubtitleFontSource?> = sourceInternal.asStateFlow()

    init {
        applicationScope.launch {
            refreshState()
        }
    }

    override suspend fun importFont(uri: Uri) {
        writeMutex.withLock {
            withContext(ioDispatcher) {
                importFontLocked(uri)
                refreshStateLocked()
            }
        }
    }

    override suspend fun clearFont() {
        writeMutex.withLock {
            withContext(ioDispatcher) {
                clearFormalArtifacts()
                clearTempArtifacts()
                refreshStateLocked()
            }
        }
    }

    private suspend fun refreshState() {
        writeMutex.withLock {
            withContext(ioDispatcher) {
                refreshStateLocked()
            }
        }
    }

    private fun importFontLocked(uri: Uri) {
        val displayName = resolveDisplayName(uri)
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension == "ttf" || extension == "otf") { "Unsupported font extension: $extension" }

        clearTempArtifacts()
        val tempFontFile = context.externalSubtitleFontTempFile
        val tempMetaFile = context.externalSubtitleFontTempMetaFile

        runCatching {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFontFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: error("Unable to open font input stream")

            validateFontFile(tempFontFile)

            tempMetaFile.writeText(
                buildJsonObject {
                    put("displayName", displayName)
                }.toString(),
            )

            commitTempArtifacts(tempFontFile = tempFontFile, tempMetaFile = tempMetaFile)
        }.onFailure { throwable ->
            clearTempArtifacts()
            Logger.error(TAG, "Failed to import subtitle font: $displayName", throwable)
            throw throwable
        }
    }

    private fun refreshStateLocked() {
        clearTempArtifacts()

        val fontFile = context.externalSubtitleFontFile
        val metaFile = context.externalSubtitleFontMetaFile

        if (!fontFile.exists() && !metaFile.exists()) {
            publishEmptyState()
            return
        }

        val meta = readMeta(metaFile)
        if (meta == null || !fontFile.exists()) {
            clearFormalArtifacts()
            publishEmptyState()
            return
        }

        val isFontValid = runCatching {
            validateFontFile(fontFile)
        }.isSuccess
        if (!isFontValid) {
            clearFormalArtifacts()
            publishEmptyState()
            return
        }

        stateInternal.value = ExternalSubtitleFontState(
            isAvailable = true,
            displayName = meta.displayName,
        )
        sourceInternal.value = ExternalSubtitleFontSource(
            absolutePath = fontFile.absolutePath,
        )
    }

    private fun readMeta(metaFile: File): ExternalSubtitleFontMeta? {
        if (!metaFile.exists()) return null
        return runCatching {
            val jsonObject = json.parseToJsonElement(metaFile.readText()).jsonObject
            ExternalSubtitleFontMeta(
                displayName = jsonObject.getValue("displayName").jsonPrimitive.content,
            )
        }.onFailure { throwable ->
            Logger.error(TAG, "Failed to read external subtitle font meta", throwable)
        }.getOrNull()
    }

    private fun resolveDisplayName(uri: Uri): String {
        val displayName = context.contentResolver.queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: ""
        require(displayName.isNotBlank()) { "Unable to resolve font display name" }
        return displayName
    }

    private fun ContentResolver.queryDisplayName(uri: Uri): String? = query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index == -1) return@use null
        cursor.getString(index)
    }

    private fun validateFontFile(file: File) {
        subtitleFontFileValidator.validate(file)
    }

    private fun commitTempArtifacts(
        tempFontFile: File,
        tempMetaFile: File,
    ) {
        val formalFontFile = context.externalSubtitleFontFile
        val formalMetaFile = context.externalSubtitleFontMetaFile
        val fontBackupFile = File(context.externalSubtitleFontDir, "current.font.bak")
        val metaBackupFile = File(context.externalSubtitleFontDir, "current.json.bak")

        runCatching {
            backupIfExists(formalFontFile, fontBackupFile)
            backupIfExists(formalMetaFile, metaBackupFile)

            moveFile(tempFontFile, formalFontFile)
            moveFile(tempMetaFile, formalMetaFile)

            fontBackupFile.delete()
            metaBackupFile.delete()
        }.onFailure { throwable ->
            restoreBackup(formalFontFile, fontBackupFile)
            restoreBackup(formalMetaFile, metaBackupFile)
            Logger.error(TAG, "Failed to commit subtitle font artifacts", throwable)
            throw throwable
        }
    }

    private fun backupIfExists(
        sourceFile: File,
        backupFile: File,
    ) {
        if (!sourceFile.exists()) return
        moveFile(sourceFile, backupFile)
    }

    private fun restoreBackup(
        targetFile: File,
        backupFile: File,
    ) {
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!backupFile.exists()) return
        moveFile(backupFile, targetFile)
    }

    private fun moveFile(
        sourceFile: File,
        targetFile: File,
    ) {
        targetFile.parentFile?.mkdirs()
        try {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (exception: IOException) {
            throw exception
        }
    }

    private fun clearFormalArtifacts() {
        context.externalSubtitleFontFile.delete()
        context.externalSubtitleFontMetaFile.delete()
    }

    private fun clearTempArtifacts() {
        context.externalSubtitleFontTempFile.delete()
        context.externalSubtitleFontTempMetaFile.delete()
    }

    private fun publishEmptyState() {
        stateInternal.value = ExternalSubtitleFontState()
        sourceInternal.value = null
    }
}
