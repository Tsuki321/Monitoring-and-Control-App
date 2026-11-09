package com.watermonitor.app.data.history

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object ArchiveCalendar {
    val zone: ZoneId = ZoneId.of("Asia/Manila")
    fun month(time: Long): String = YearMonth.from(Instant.ofEpochMilli(time).atZone(zone)).toString()
    fun start(month: String): Long = YearMonth.parse(month).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun end(month: String): Long = YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

@Entity(tableName = "history", indices = [Index("month"), Index("uploaded")])
data class HistoryRecord(
    @PrimaryKey val id: String,
    val month: String,
    val time: Long,
    val kind: String,
    val payload: String,
    val uploaded: Boolean = false
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: HistoryRecord)

    @Query("SELECT * FROM history WHERE month = :month ORDER BY time")
    fun observe(month: String): Flow<List<HistoryRecord>>

    @Query("SELECT DISTINCT month FROM history ORDER BY month DESC")
    fun months(): Flow<List<String>>

    @Query("SELECT * FROM history WHERE uploaded = 0 AND month < :currentMonth ORDER BY time LIMIT 100")
    suspend fun pending(currentMonth: String): List<HistoryRecord>

    @Query("UPDATE history SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun acknowledge(ids: List<String>)

    @Query("SELECT COUNT(*) FROM history WHERE uploaded = 0 AND month < :currentMonth")
    fun pendingCount(currentMonth: String): Flow<Int>
}

@Database(entities = [HistoryRecord::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    companion object {
        @Volatile private var instance: HistoryDatabase? = null
        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java,
                "hydrosense-history.db").build().also { instance = it }
        }
    }
}
