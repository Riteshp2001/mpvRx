package app.gyrolet.mpvrx.exoplayer

interface MediaSynchronizer {
    suspend fun refresh(path: String? = null): Boolean
    suspend fun registerManualVideoPath(path: String)
    fun startSync()
    fun stopSync()
}
