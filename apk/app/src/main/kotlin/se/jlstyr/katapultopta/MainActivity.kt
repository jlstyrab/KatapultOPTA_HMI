package se.jlstyr.katapultopta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Katapult Opta HMI — plattan är en skärm, Optan äger projektet.
 *
 * En WebView med katapult-scadas runtime (assets/www, serverad av AssetServer
 * på 127.0.0.1). Runtimen får bara Optans adress (?opta=) och hämtar
 * driftkortet ur PLC:ns flash (GET /api/hmi/scene). Optan hittas med
 * Katapults UDP-upptäckt; nätverket där den svarade binds processen till.
 * Inställningar: håll volym ned i 3 s. Kiosk: app-fästning (startLockTask),
 * skärmen alltid på, HOME-kategori i manifestet för autostart.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var server: AssetServer
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var volDownSince = 0L
    private var currentIp: String? = null
    private val holdCheck = Runnable {
        if (volDownSince != 0L && System.currentTimeMillis() - volDownSince >= HOLD_MS) {
            volDownSince = 0L
            openSettings(null)
        }
    }

    companion object {
        private const val HOLD_MS = 3000L
        private const val FALLBACK_IP = "192.168.200.1"   // Optans passiva fallback utan DHCP
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("opta", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        server = AssetServer(assets).also { it.start() }

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                // Själva skalet nåddes inte: släpp ev. nätbindning (loopback ska alltid gå)
                // och försök igen om 5 s.
                if (request.url?.host == "127.0.0.1") bindTo(null)
                ui.postDelayed({ view.reload() }, 5000)
            }
        }
        setContentView(web)
        immersive()

        if (prefs.getBoolean("kiosk", false)) {
            try { startLockTask() } catch (_: Exception) {}
        }
        showSplash("Söker Optan…")
        connect()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun showSplash(text: String) {
        val html = "<html><body style='margin:0;background:#0e1116;color:#dbe2ea;font:20px system-ui;" +
            "display:flex;align-items:center;justify-content:center;height:100vh'>$text</body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    // ── Hitta Optan + nätbindning ──────────────────────────────────────────

    private fun connect() {
        val saved = prefs.getString("ip", null)
        Thread({
            val hits = try { Discovery.find(this) } catch (_: Exception) { emptyList() }
            ui.post {
                val chosen: Discovery.Hit? = when {
                    hits.isEmpty() -> null
                    saved != null && hits.any { it.ip == saved } -> hits.first { it.ip == saved }
                    hits.size == 1 -> hits[0]
                    else -> null
                }
                when {
                    chosen != null -> { bindTo(chosen.network); load(chosen.ip) }
                    hits.size > 1 -> chooseAmong(hits)
                    saved != null -> { bindTo(null); load(saved) }
                    else -> openSettings("Hittade ingen Opta på nätet — ange adressen.")
                }
            }
        }, "opta-discover").start()
    }

    private fun bindTo(network: Network?) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(network)
        } catch (_: Exception) {}
    }

    private fun load(ip: String) {
        currentIp = ip
        prefs.edit().putString("ip", ip).apply()
        web.loadUrl("http://127.0.0.1:${server.port}/?opta=$ip")
    }

    private fun chooseAmong(hits: List<Discovery.Hit>) {
        val names = hits.map { "${it.name} · ${it.ip}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Flera Optor hittades")
            .setItems(names) { _, i -> bindTo(hits[i].network); load(hits[i].ip) }
            .setCancelable(false)
            .show()
    }

    // ── Inställningar (volym ned 3 s) ───────────────────────────────────────

    private fun openSettings(note: String?) {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val info = TextView(this).apply {
            text = (if (note != null) "$note\n\n" else "") +
                "Opta: ${currentIp ?: "—"}\nApp: $version\n" +
                "Kiosk (fäst app): ${if (prefs.getBoolean("kiosk", false)) "PÅ" else "AV"}"
        }
        val ipField = EditText(this).apply {
            hint = "Optans adress"
            setText(currentIp ?: prefs.getString("ip", null) ?: FALLBACK_IP)
            setSingleLine(true)
        }
        box.addView(info)
        box.addView(ipField)
        AlertDialog.Builder(this)
            .setTitle("Katapult Opta HMI")
            .setView(box)
            .setPositiveButton("Använd adressen") { _, _ ->
                val ip = ipField.text.toString().trim()
                if (ip.isNotEmpty()) { showSplash("Ansluter till $ip…"); bindTo(null); load(ip) }
            }
            .setNeutralButton("Sök igen") { _, _ -> showSplash("Söker Optan…"); connect() }
            .setNegativeButton(if (prefs.getBoolean("kiosk", false)) "Kiosk av" else "Kiosk på") { _, _ -> toggleKiosk() }
            .show()
    }

    private fun toggleKiosk() {
        val on = !prefs.getBoolean("kiosk", false)
        prefs.edit().putBoolean("kiosk", on).apply()
        try {
            if (on) startLockTask() else stopLockTask()
        } catch (e: Exception) {
            Toast.makeText(this, "Kunde inte ändra fästning: ${e.message}", Toast.LENGTH_LONG).show()
        }
        Toast.makeText(this, if (on) "Appen fäst (kiosk på)" else "Fästning av", Toast.LENGTH_SHORT).show()
    }

    // ── Knappar: volym ned hållen = inställningar; Back gör inget (kiosk) ──

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volDownSince == 0L) {
                volDownSince = System.currentTimeMillis()
                ui.postDelayed(holdCheck, HOLD_MS)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volDownSince = 0L
            ui.removeCallbacks(holdCheck)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Kiosk: Back gör inget")
    override fun onBackPressed() {
        // avsiktligt tomt
    }

    override fun onDestroy() {
        try { server.stop() } catch (_: Exception) {}
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
