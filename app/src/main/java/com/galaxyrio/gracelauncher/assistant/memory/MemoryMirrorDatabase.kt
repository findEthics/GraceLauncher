package com.galaxyrio.gracelauncher.assistant.memory

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

/**
 * Local mirror of the server memory ledger. Separate from `grace_launcher.db`; excluded from backup
 * (backup_rules.xml / data_extraction_rules.xml). Never destructive: the outbox holds unsynced writes.
 * `rowId` is the integer rowid the FTS4 content table needs; `id` (server uuid) is unique.
 */
@Entity(tableName = "entries", indices = [Index(value = ["id"], unique = true)])
internal data class EntryRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val id: String,
    val type: String,
    val subject: String,
    val subjectKey: String,
    val body: String,
    val occurredAt: Long?,
    val validFrom: Long,
    val validTo: Long?,
    val status: String,
    val supersedes: String?,
    val supersededBy: String?,
    val source: String,
    val createdAt: Long,
    val changeSeq: Long,
    val conflict: Boolean,
    val serverConflict: Boolean,
)

@Fts4(contentEntity = EntryRow::class)
@Entity(tableName = "entries_fts")
internal data class EntryFts(
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "body") val body: String,
)

@Entity(tableName = "outbox")
internal data class OutboxRow(
    @PrimaryKey val key: String,
    val seq: Long,
    val kind: String,
    val entryJson: String?,
    val supersedeId: String?,
    val targetId: String?,
    val attempts: Int,
)

@Entity(tableName = "sync_state")
internal data class SyncStateRow(@PrimaryKey val id: Int = 0, val cursor: Long, val lastSyncAt: Long?)

internal class RowRef(val id: String, val rowId: Long)

/** Plain operations only; ordering/upsert logic lives in [RoomMemoryStore] so it is testable with a fake. */
@Dao
internal interface MemoryDao {
    @Query("SELECT * FROM entries ORDER BY validFrom, rowId")
    fun entries(): List<EntryRow>

    @Query("SELECT id, rowId FROM entries")
    fun rowRefs(): List<RowRef>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(rows: List<EntryRow>)

    /** UPDATE (not REPLACE) so the FTS content triggers fire. */
    @Update
    fun update(rows: List<EntryRow>)

    @Query("DELETE FROM entries WHERE id IN (:ids)")
    fun delete(ids: List<String>)

    /** FTS4 MATCH over subject+body; ids of matching rows. */
    @Query("SELECT e.id FROM entries e JOIN entries_fts f ON f.docid = e.rowId WHERE entries_fts MATCH :match")
    fun search(match: String): List<String>

    @Query("SELECT * FROM outbox ORDER BY seq")
    fun outbox(): List<OutboxRow>

    @Query("SELECT MAX(seq) FROM outbox")
    fun maxOutboxSeq(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun enqueue(row: OutboxRow)

    @Query("DELETE FROM outbox WHERE `key` = :key")
    fun removeOp(key: String)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE `key` = :key")
    fun bumpAttempts(key: String)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun state(): SyncStateRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putState(row: SyncStateRow)
}

@Database(entities = [EntryRow::class, EntryFts::class, OutboxRow::class, SyncStateRow::class], version = 1, exportSchema = true)
internal abstract class MemoryMirrorDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        const val NAME = "memory_mirror.db"
        @Volatile private var instance: MemoryMirrorDatabase? = null
        fun getInstance(context: Context): MemoryMirrorDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, MemoryMirrorDatabase::class.java, NAME).build().also { instance = it }
        }
    }
}
