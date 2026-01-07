package pub.smartnet.lifepal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted, starting foreground service")
            
            // Start foreground service which will handle worker scheduling
            LifePalForegroundService.startService(context)
            
            Log.d("BootReceiver", "Foreground service started on boot")
        }
    }
}
