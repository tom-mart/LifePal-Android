package pub.smartnet.lifepal.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queued_contextual_data")
data class QueuedContextualData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: String, // When data was collected
    val payload: String,   // JSON serialized ContextualDataPayload
    val createdAt: Long = System.currentTimeMillis()
)
