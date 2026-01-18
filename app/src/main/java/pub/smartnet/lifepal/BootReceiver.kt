package pub.smartnet.lifepal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d("BootReceiver", "Boot completed: ${intent.action}, starting foreground service")
                
                // Start foreground service which will handle worker scheduling
                LifePalForegroundService.startService(context)
                
                Log.d("BootReceiver", "Foreground service started on boot")
            }
        }
    }
}
