package pub.smartnet.lifepal

import android.app.Application
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class LifePalApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("LifePalApplication", "Application starting")
        
        // Start foreground service for reliable background work
        LifePalForegroundService.startService(this)
        
        Log.d("LifePalApplication", "Foreground service started")
    }

}
