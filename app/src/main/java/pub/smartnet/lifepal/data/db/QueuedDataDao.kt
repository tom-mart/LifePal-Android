package pub.smartnet.lifepal.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface QueuedDataDao {
    // Health Data
    @Insert
    suspend fun insertHealthData(data: QueuedHealthData): Long
    
    @Query("SELECT * FROM queued_health_data ORDER BY createdAt ASC")
    suspend fun getAllHealthData(): List<QueuedHealthData>
    
    @Query("DELETE FROM queued_health_data WHERE id = :id")
    suspend fun deleteHealthData(id: Long)
    
    @Query("SELECT COUNT(*) FROM queued_health_data")
    suspend fun getHealthDataCount(): Int
    
    // Contextual Data
    @Insert
    suspend fun insertContextualData(data: QueuedContextualData): Long
    
    @Query("SELECT * FROM queued_contextual_data ORDER BY createdAt ASC")
    suspend fun getAllContextualData(): List<QueuedContextualData>
    
    @Query("DELETE FROM queued_contextual_data WHERE id = :id")
    suspend fun deleteContextualData(id: Long)
    
    @Query("SELECT COUNT(*) FROM queued_contextual_data")
    suspend fun getContextualDataCount(): Int
}
