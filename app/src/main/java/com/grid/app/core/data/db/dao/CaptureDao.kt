package com.grid.app.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.grid.app.core.data.db.entities.CaptureEntity
import com.grid.app.core.model.CaptureSource
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {
    /** Returns -1 when an identical capture (same dedupe key) already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(capture: CaptureEntity): Long

    @Update
    suspend fun update(capture: CaptureEntity)

    @Query("SELECT * FROM captures WHERE id = :id")
    suspend fun get(id: Long): CaptureEntity?

    @Query("SELECT * FROM captures WHERE status = 'NEW' ORDER BY postedAt DESC")
    fun observeInbox(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE status = 'UNPARSED' ORDER BY postedAt DESC")
    fun observeUnparsed(): Flow<List<CaptureEntity>>

    @Query("SELECT * FROM captures WHERE status = 'ADDED' AND postedAt >= :sinceMs ORDER BY postedAt DESC")
    fun observeAddedSince(sinceMs: Long): Flow<List<CaptureEntity>>

    /** The same payment re-posted (notification updated) within a few minutes. */
    @Query(
        """SELECT * FROM captures WHERE source = :source AND amountMinor = :amountMinor AND currency = :currency
           AND postedAt BETWEEN :fromMs AND :toMs AND status != 'UNPARSED' LIMIT 1""",
    )
    suspend fun findSimilar(source: CaptureSource, amountMinor: Long, currency: String, fromMs: Long, toMs: Long): CaptureEntity?

    @Query("DELETE FROM captures WHERE status = 'UNPARSED' AND postedAt < :beforeMs")
    suspend fun pruneUnparsed(beforeMs: Long)

    @Query("DELETE FROM captures WHERE status = 'UNPARSED'")
    suspend fun clearUnparsed()
}
