package app.gyrolet.mpvrx.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.gyrolet.mpvrx.database.entities.Audiobook
import app.gyrolet.mpvrx.database.entities.AudiobookChapterEntity
import app.gyrolet.mpvrx.database.entities.AudiobookEntity
import app.gyrolet.mpvrx.database.entities.AudiobookTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AudiobookDao {
  @Transaction
  @Query("SELECT * FROM audiobooks ORDER BY lastPlayedAt DESC, title COLLATE NOCASE")
  abstract fun observeLibrary(): Flow<List<Audiobook>>

  @Transaction
  @Query("SELECT * FROM audiobooks WHERE id = :id")
  abstract suspend fun getBook(id: Long): Audiobook?

  @Transaction
  @Query("SELECT * FROM audiobooks WHERE id = :id")
  abstract fun observeBook(id: Long): Flow<Audiobook?>

  @Query("SELECT id FROM audiobooks WHERE sourceKey = :sourceKey")
  abstract suspend fun findBySource(sourceKey: String): Long?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  abstract suspend fun insertBook(book: AudiobookEntity): Long

  @Insert
  abstract suspend fun insertTracks(tracks: List<AudiobookTrackEntity>): List<Long>

  @Transaction
  open suspend fun importBook(
    book: AudiobookEntity,
    tracks: List<AudiobookTrackEntity>,
  ): Long {
    require(tracks.isNotEmpty())
    findBySource(book.sourceKey)?.let { return it }
    val id = insertBook(book)
    if (id == -1L) return requireNotNull(findBySource(book.sourceKey))
    val trackIds = insertTracks(tracks.mapIndexed { index, track -> track.copy(id = 0, bookId = id, position = index) })
    setInitialTrack(id, trackIds.first())
    return id
  }

  @Query("UPDATE audiobooks SET currentTrackId = :trackId WHERE id = :id")
  protected abstract suspend fun setInitialTrack(
    id: Long,
    trackId: Long,
  )

  @Query(
    """UPDATE audiobooks SET currentTrackId = :trackId,
    positionMs = CASE WHEN :reachedEnd THEN (SELECT durationMs FROM audiobook_tracks WHERE id = :trackId) ELSE :positionMs END,
    progressMs = (CASE WHEN :reachedEnd THEN (SELECT durationMs FROM audiobook_tracks WHERE id = :trackId) ELSE :positionMs END)
      + (SELECT COALESCE(SUM(durationMs), 0) FROM audiobook_tracks
      WHERE bookId = :bookId AND position < (SELECT position FROM audiobook_tracks WHERE id = :trackId)),
    lastPlayedAt = :playedAt, finished = CASE WHEN :reachedEnd AND NOT EXISTS
      (SELECT 1 FROM audiobook_tracks WHERE bookId = :bookId AND position >
        (SELECT position FROM audiobook_tracks WHERE id = :trackId)) THEN 1 ELSE 0 END
    WHERE id = :bookId AND EXISTS (SELECT 1 FROM audiobook_tracks WHERE id = :trackId AND bookId = :bookId)""",
  )
  abstract suspend fun saveProgress(
    bookId: Long,
    trackId: Long,
    positionMs: Long,
    playedAt: Long,
    reachedEnd: Boolean,
  )

  @Query("UPDATE audiobooks SET playbackSpeed = :speed WHERE id = :id")
  abstract suspend fun setSpeed(
    id: Long,
    speed: Float,
  )

  @Query("UPDATE audiobooks SET rewindSeconds = :seconds WHERE id = :id")
  abstract suspend fun setRewind(
    id: Long,
    seconds: Int,
  )

  @Query("UPDATE audiobooks SET finished = :finished WHERE id = :id")
  abstract suspend fun setFinished(
    id: Long,
    finished: Boolean,
  )

  @Query(
    """UPDATE audiobooks SET title = :title, author = :author, narrator = :narrator,
    series = :series, seriesPart = :seriesPart WHERE id = :id""",
  )
  abstract suspend fun updateDetails(
    id: Long,
    title: String,
    author: String,
    narrator: String,
    series: String,
    seriesPart: String,
  )

  @Query(
    """UPDATE audiobooks SET title = :title, author = :author, narrator = :narrator,
    series = :series, seriesPart = :seriesPart, coverUri = :coverUri WHERE id = :id""",
  )
  abstract suspend fun updateDetails(
    id: Long,
    title: String,
    author: String,
    narrator: String,
    series: String,
    seriesPart: String,
    coverUri: String?,
  )

  @Query("UPDATE audiobooks SET coverUri = :coverUri WHERE id = :id")
  abstract suspend fun updateCover(
    id: Long,
    coverUri: String?,
  )

  @Query(
    """UPDATE audiobooks SET
    author = CASE WHEN author = '' THEN :author ELSE author END,
    narrator = CASE WHEN narrator = '' THEN :narrator ELSE narrator END,
    series = CASE WHEN series = '' THEN :series ELSE series END,
    seriesPart = CASE WHEN seriesPart = '' THEN :seriesPart ELSE seriesPart END,
    description = CASE WHEN description = '' THEN :description ELSE description END,
    language = CASE WHEN language = '' THEN :language ELSE language END,
    publisher = CASE WHEN publisher = '' THEN :publisher ELSE publisher END,
    publishedYear = CASE WHEN publishedYear = '' THEN :publishedYear ELSE publishedYear END,
    genre = CASE WHEN genre = '' THEN :genre ELSE genre END,
    isbn = CASE WHEN isbn = '' THEN :isbn ELSE isbn END,
    asin = CASE WHEN asin = '' THEN :asin ELSE asin END WHERE id = :id""",
  )
  abstract suspend fun fillMetadata(
    id: Long,
    author: String,
    narrator: String,
    series: String,
    seriesPart: String,
    description: String,
    language: String,
    publisher: String,
    publishedYear: String,
    genre: String,
    isbn: String,
    asin: String,
  )

  @Query("UPDATE audiobook_tracks SET durationMs = :durationMs WHERE id = :trackId AND :durationMs > 0")
  abstract suspend fun updateDuration(
    trackId: Long,
    durationMs: Long,
  )

  @Query("SELECT * FROM audiobook_chapters WHERE trackId = :trackId ORDER BY startMs")
  abstract suspend fun getChapters(trackId: Long): List<AudiobookChapterEntity>

  @Query("DELETE FROM audiobooks WHERE id = :id")
  abstract suspend fun deleteBook(id: Long)

  @Query(
    "SELECT * FROM audiobook_chapters WHERE trackId IN (SELECT id FROM audiobook_tracks WHERE bookId = :bookId) ORDER BY startMs",
  )
  abstract fun observeChapters(bookId: Long): Flow<List<AudiobookChapterEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  protected abstract suspend fun insertChapters(chapters: List<AudiobookChapterEntity>)

  @Query("DELETE FROM audiobook_chapters WHERE trackId = :trackId")
  protected abstract suspend fun deleteChapters(trackId: Long)

  @Transaction
  open suspend fun replaceChapters(
    trackId: Long,
    chapters: List<AudiobookChapterEntity>,
  ) {
    require(chapters.all { it.trackId == trackId && it.startMs >= 0 && it.endMs > it.startMs })
    deleteChapters(trackId)
    if (chapters.isNotEmpty()) insertChapters(chapters.distinctBy { it.startMs })
  }

  @Query("SELECT * FROM audiobooks")
  abstract suspend fun getAllBooks(): List<AudiobookEntity>

  @Query("SELECT * FROM audiobook_tracks")
  abstract suspend fun getAllTracks(): List<AudiobookTrackEntity>
}
