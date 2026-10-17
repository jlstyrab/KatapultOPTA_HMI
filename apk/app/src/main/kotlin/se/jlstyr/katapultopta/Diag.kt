package se.jlstyr.katapultopta

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diagnoslogg som visas i inställningsdialogen (volym ned 3 s) — paddan i
 *  fält har ingen adb. Allt viktigt (server, upptäckt, bindning, sidfel)
 *  skrivs hit och till Logcat. */
object Diag {
    private const val TAG = "KatapultOpta"
    private val sb = StringBuilder()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun log(msg: String) {
        sb.append(fmt.format(Date())).append(' ').append(msg).append('\n')
        if (sb.length > 16000) sb.delete(0, sb.length - 12000)
        Log.i(TAG, msg)
    }

    @Synchronized
    fun text(): String = sb.toString()

    @Synchronized
    fun tail(lines: Int): String {
        val all = sb.toString().trimEnd().split('\n')
        return all.takeLast(lines).joinToString("\n")
    }
}
