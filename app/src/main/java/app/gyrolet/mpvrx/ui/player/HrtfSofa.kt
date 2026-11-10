package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.util.AtomicFile
import java.io.File

internal enum class HrtfStatus {
  Inactive,
  Preparing,
  Applied,
  Unavailable,
}

internal object HrtfSofa {
  const val ASSET = "hrtf/ku100_51_96k.sofa"
  const val FILTER_LABEL = "mpvrx-hrtf"
  private var preparedFile: File? = null

  @Synchronized
  fun prepare(context: Context): File {
    val target = File(context.filesDir, ASSET)
    preparedFile?.takeIf { it == target && it.isFile && it.length() > 0 }?.let { return it }
    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
    val atomic = AtomicFile(target)
    val output = atomic.startWrite()
    try {
      context.assets.open(ASSET).use { it.copyTo(output) }
      atomic.finishWrite(output)
    } catch (error: Throwable) {
      atomic.failWrite(output)
      throw error
    }
    preparedFile = target
    return target
  }

  fun filter(sofaFile: File): String {
    val path = sofaFile.absolutePath
    require(path.none { it in "'\\:[],;\r\n" })
    return "@$FILTER_LABEL:lavfi=[sofalizer=sofa='$path':type=freq]"
  }
}