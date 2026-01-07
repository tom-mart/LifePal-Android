package pub.smartnet.lifepal.data

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Lightweight sync time tracker using SharedPreferences.
 * Tracks last sync times to avoid massive data duplication.
 * Server handles precise deduplication.
 */
class SyncTimeTracker(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "lifepal_sync_times",
        Context.MODE_PRIVATE
    )

    /**
     * Get the last sync time for a metric type.
     * Returns start of today if never synced or if last sync was yesterday.
     */
    fun getLastSyncTime(metricType: String): Instant {
        val lastSyncMillis = prefs.getLong("${metricType}_last_sync", 0L)
        
        if (lastSyncMillis == 0L) {
            // Never synced - return start of today
            return getStartOfToday()
        }
        
        val lastSync = Instant.ofEpochMilli(lastSyncMillis)
        val startOfToday = getStartOfToday()
        
        // If last sync was before today, reset to start of today
        return if (lastSync.isBefore(startOfToday)) {
            startOfToday
        } else {
            lastSync
        }
    }

    /**
     * Update the last sync time for a metric type to now.
     */
    fun updateSyncTime(metricType: String) {
        prefs.edit()
            .putLong("${metricType}_last_sync", Instant.now().toEpochMilli())
            .apply()
    }

    /**
     * Get midnight (start) of today in system timezone.
     */
    private fun getStartOfToday(): Instant {
        return LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    }

    /**
     * Clear all sync times (for testing or reset).
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Get all tracked sync times for debugging.
     */
    fun getAllSyncTimes(): Map<String, Instant> {
        return prefs.all
            .filterKeys { it.endsWith("_last_sync") }
            .mapKeys { it.key.removeSuffix("_last_sync") }
            .mapValues { Instant.ofEpochMilli(it.value as Long) }
    }
}
