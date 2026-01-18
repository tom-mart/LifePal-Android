package pub.smartnet.lifepal

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class LifePalApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d("LifePalApplication", "Application starting")
        
        // Start foreground service for reliable background work
        LifePalForegroundService.startService(this)
        
        Log.d("LifePalApplication", "Foreground service started")
    }

}
