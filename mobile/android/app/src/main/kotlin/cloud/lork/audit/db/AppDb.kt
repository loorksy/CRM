package cloud.lork.audit.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "cycles")
data class CycleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "account_email") val accountEmail: String,
    val discountRate: Double,
    val agentColor: String,
    val managementColor: String,
    val columnsJson: String,
    val sourcesJson: String,
    val userInfoHash: String?,
    val active: Boolean,
    val updatedAt: Long,
    @ColumnInfo(name = "exported_note", defaultValue = "''") val exportedNote: String = "",
)

@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cycleId: Long,
    val accountEmail: String,
    val fingerprint: String,
    val complete: Boolean,
    val managementJson: String,
    val agentJson: String,
    val userInfoJson: String,
    val rowCount: Int,
    val createdAt: Long,
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val planId: String,
    val cycleId: Long,
    val accountEmail: String,
    val planJson: String,
    val status: String,
    val summary: String,
    val createdAt: Long,
)

@Entity(tableName = "operations")
data class OpEntity(
    @PrimaryKey val key: String,
    val status: String,
    val detail: String,
)

@Entity(tableName = "audit_marks")
data class AuditMarkEntity(
    @PrimaryKey val id: String,
    val cycleId: Long,
    val userId: String,
    val status: String,
    val source: String,
    val detail: String,
)

@Entity(tableName = "send_session")
data class SendSessionEntity(
    @PrimaryKey val id: Int = 1,
    val state: String,
    val idx: Int,
    val mpm: Int,
    val chatKey: String?,
    val rawText: String,
)

@Entity(tableName = "send_items")
data class SendItemEntity(
    @PrimaryKey val itemId: String,
    val position: Int,
    val body: String,
    val status: String,
    val detail: String,
)

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val k: String,
    val v: String,
)

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveCycle(cycle: CycleEntity): Long

    @Query("SELECT * FROM cycles WHERE account_email = :email ORDER BY updatedAt DESC")
    fun cycles(email: String): List<CycleEntity>

    @Query("SELECT * FROM cycles WHERE id = :id")
    fun cycle(id: Long): CycleEntity?

    @Query("UPDATE cycles SET active = 0 WHERE account_email = :email")
    fun clearActive(email: String)

    @Query("UPDATE cycles SET active = 1, updatedAt = :now WHERE id = :id")
    fun markActive(id: Long, now: Long)

    @Query("SELECT * FROM cycles WHERE account_email = :email AND active = 1 LIMIT 1")
    fun activeCycle(email: String): CycleEntity?

    @Insert
    fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Query("SELECT * FROM snapshots WHERE cycleId = :cycleId AND accountEmail = :email AND complete = 1 ORDER BY createdAt DESC LIMIT 1")
    fun latestSnapshot(cycleId: Long, email: String): SnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun savePlan(plan: PlanEntity)

    @Query("SELECT * FROM plans WHERE planId = :id")
    fun plan(id: String): PlanEntity?

    @Query("SELECT * FROM plans WHERE cycleId = :cycleId ORDER BY createdAt DESC LIMIT 1")
    fun latestPlan(cycleId: Long): PlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveOp(op: OpEntity)

    @Query("SELECT * FROM operations WHERE key = :key")
    fun op(key: String): OpEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveMark(mark: AuditMarkEntity)

    @Query("SELECT * FROM audit_marks WHERE cycleId = :cycleId")
    fun marks(cycleId: Long): List<AuditMarkEntity>

    @Query("SELECT * FROM audit_marks WHERE cycleId = :cycleId AND userId = :userId LIMIT 1")
    fun mark(cycleId: Long, userId: String): AuditMarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSession(session: SendSessionEntity)

    @Query("SELECT * FROM send_session WHERE id = 1")
    fun session(): SendSessionEntity?

    @Query("DELETE FROM send_items")
    fun clearItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertItems(items: List<SendItemEntity>)

    @Query("SELECT * FROM send_items ORDER BY position")
    fun items(): List<SendItemEntity>

    @Query("UPDATE send_items SET status = :status, detail = :detail WHERE itemId = :id")
    fun updateItem(id: String, status: String, detail: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveKv(row: KvEntity)

    @Query("SELECT v FROM kv WHERE k = :key")
    fun kv(key: String): String?

    @Query("SELECT * FROM cycles")
    fun allCycles(): List<CycleEntity>

    @Query("SELECT * FROM snapshots WHERE complete = 1")
    fun allSnapshots(): List<SnapshotEntity>
}

@Database(
    entities = [
        CycleEntity::class,
        SnapshotEntity::class,
        PlanEntity::class,
        OpEntity::class,
        AuditMarkEntity::class,
        SendSessionEntity::class,
        SendItemEntity::class,
        KvEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cycles ADD COLUMN exported_note TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): AppDb = Room.databaseBuilder(context, AppDb::class.java, "lork-audit.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade()
            .allowMainThreadQueries()
            .build()
    }
}
