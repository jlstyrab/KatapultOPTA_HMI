package se.jlstyr.katapultopta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Autostart efter strömavbrott (bästa försök — på Android 10+ får en
 *  mottagare normalt inte starta en aktivitet; välj då appen som startskärm,
 *  HOME-kategorin i manifestet, så startar den ändå). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {
            // blockerad bakgrundsstart — startskärmsvägen tar över
        }
    }
}
