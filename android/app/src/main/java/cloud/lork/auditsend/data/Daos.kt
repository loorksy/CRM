package cloud.lork.auditsend.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CycleDao {
    @Query("SELECT * FROM cycles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CycleEntity>>

    @Query("SELECT * FROM cycles WHERE id = :id")
    fun observe(id: Long): Flow<CycleEntity?>

    @Insert
    suspend fun insert(cycle: CycleEntity): Long

    @Update
    suspend fun update(cycle: CycleEntity)

    @Delete
    suspend fun delete(cycle: CycleEntity)
}

@Dao
interface SheetDao {
    @Query("SELECT * FROM sheets WHERE cycleId = :cycleId")
    fun observe(cycleId: Long): Flow<List<SheetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sheet: SheetEntity)
}
