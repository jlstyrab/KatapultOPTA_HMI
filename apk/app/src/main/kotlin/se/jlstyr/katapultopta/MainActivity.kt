package se.jlstyr.katapultopta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/**
 * Katapult Opta HMI — plattan är en skärm, Optan äger projektet.
 *
 * En WebView med katapult-scadas runtime (assets/www, serverad av AssetServer
 * på 127.0.0.1). Runtimen får bara Optans adress (?opta=) och hämtar
 * driftkortet ur PLC:ns flash (GET /api/hmi/scene). Optan hittas med
 * Katapults UDP-upptäckt; sitter den på en KABEL binds processen till det
 * nätverket (Wi-Fi är redan standardrutt). Inställningar + diagnoslogg: håll
 * volym ned i 3 s. Kiosk: app-fästning, skärmen alltid på, HOME-kategori.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var overlay: TextView
    private lateinit var server: AssetServer
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private var volDownSince = 0L
    private var currentIp: String? = null
    private var boundTo: String = "ingen"
    private val holdCheck = Runnable {
        if (volDownSince != 0L && System.currentTimeMillis() - volDownSince >= HOLD_MS) {
            volDownSince = 0L
            openSettings(null)
        }
    }
    private val retry = Runnable { web.reload() }

    companion object {
        private const val HOLD_MS = 3000L
        private const val FALLBACK_IP = "192.168.200.1"   // Optans passiva fallback utan DHCP
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("opta", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Diag.log("start, version ${versionName()}")

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
                val msg = "sidfel ${error.errorCode} ${error.description} vid ${request.url}"
                Diag.log(msg)
                if (request.url?.host == "127.0.0.1") {
                    // Skalet nåddes inte: släpp ev. nätbindning (loopback ska alltid gå)
                    bindTo(null, "sidfel")
                    Thread({ Diag.log("självtest efter sidfel: ${server.selfTest()}") }, "selftest").start()
                }
                showOverlay("Kunde inte ladda $msg\n\nFörsöker igen om 10 s. Volym ned 3 s = diagnos.\n\n" + Diag.tail(8))
                ui.removeCallbacks(retry)
                ui.postDelayed(retry, 10000)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("http://127.0.0.1")) overlay.visibility = View.GONE
            }
        }

        overlay = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#cc0e1116"))
            setTextColor(Color.parseColor("#dbe2ea"))
            textSize = 14f
            val pad = (18 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        val root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        immersive()

        if (prefs.getBoolean("kiosk", false)) {
            try { startLockTask() } catch (e: Exception) { Diag.log("startLockTask: ${e.message}") }
        }
        Thread({ Diag.log("självtest assetserver: ${server.selfTest()}") }, "selftest").start()
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

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun showSplash(text: String) {
        val html = "<html><body style='margin:0;background:#0e1116;color:#dbe2ea;font:20px system-ui;" +
            "display:flex;align-items:center;justify-content:center;height:100vh'>$text</body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun showOverlay(text: String) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
    }

    // ── Hitta Optan + nätbindning ──────────────────────────────────────────

    private fun connect() {
        val saved = prefs.getString("ip", null)
        Thread({
            val hits = try { Discovery.find(this) } catch (e: Exception) { Diag.log("upptäckt kraschade: $e"); emptyList() }
            // Inget svar på broadcast men en sparad adress: fråga den direkt.
            val direct = if (hits.isEmpty() && saved != null) Discovery.ping(saved) else null
            ui.post {
                val chosen: Discovery.Hit? = when {
                    hits.isEmpty() -> direct
                    saved != null && hits.any { it.ip == saved } -> hits.first { it.ip == saved }
                    hits.size == 1 -> hits[0]
                    else -> null
                }
                when {
                    chosen != null -> { bindFor(chosen); load(chosen.ip) }
                    hits.size > 1 -> chooseAmong(hits)
                    saved != null -> { Diag.log("ingen Opta svarade — laddar sparad adress $saved ändå"); bindTo(null, "sparad adress"); load(saved) }
                    else -> openSettings("Hittade ingen Opta på nätet — ange adressen.")
                }
            }
        }, "opta-discover").start()
    }

    /** Bind bara när Optan sitter på kabel: Wi-Fi är redan standardrutt, och en
     *  bindning som inte behövs riskerar bara loopbacken till assetservern. */
    private fun bindFor(hit: Discovery.Hit) {
        if (hit.network != null && Discovery.isEthernet(this, hit.network)) bindTo(hit.network, "Ethernet där ${hit.name} svarade")
        else bindTo(null, "Optan på Wi-Fi/standardnät")
    }

    private fun bindTo(network: Network?, why: String) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ok = cm.bindProcessToNetwork(network)
            boundTo = if (network == null) "ingen" else network.toString()
            Diag.log("nätbindning → $boundTo ($why): ${if (ok) "ok" else "misslyckades"}")
            if (network != null) Thread({
                val t = server.selfTest()
                Diag.log("självtest efter bindning: $t")
                if (t.startsWith("FEL")) ui.post { bindTo(null, "loopback bröts av bindningen") }
            }, "selftest").start()
        } catch (e: Exception) { Diag.log("nätbindning: ${e.message}") }
    }

    private fun load(ip: String) {
        currentIp = ip
        prefs.edit().putString("ip", ip).apply()
        val url = "http://127.0.0.1:${server.port}/?opta=$ip"
        Diag.log("laddar $url")
        overlay.visibility = View.GONE
        web.loadUrl(url)
    }

    private fun chooseAmong(hits: List<Discovery.Hit>) {
        val names = hits.map { "${it.name} · ${it.ip}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Flera Optor hittades")
            .setItems(names) { _, i -> bindFor(hits[i]); load(hits[i].ip) }
            .setCancelable(false)
            .show()
    }

    /** HTTP-koll mot Optan (bakgrundstråd): svarar PLC:n och har den ett kort? */
    private fun testOpta(ip: String) {
        Thread({
            val r = try {
                val c = URL("http://$ip/api/hmi/info").openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 4000
                val code = c.responseCode
                val body = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                c.disconnect()
                "HTTP $code ${body.take(140)}"
            } catch (e: Exception) { "FEL ${e.javaClass.simpleName}: ${e.message}" }
            Diag.log("Opta $ip /api/hmi/info: $r")
            ui.post { Toast.makeText(this, "Opta $ip: $r", Toast.LENGTH_LONG).show() }
        }, "opta-test").start()
    }

    // ── Inställningar + diagnos (volym ned 3 s) ────────────────────────────

    private fun openSettings(note: String?) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * d).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val info = TextView(this).apply {
            text = (if (note != null) "$note\n\n" else "") +
                "Opta: ${currentIp ?: "—"}   App: ${versionName()}\n" +
                "Assetserver: 127.0.0.1:${server.port} (${server.requests} anrop)   Nätbindning: $boundTo\n" +
                "Kiosk (fäst app): ${if (prefs.getBoolean("kiosk", false)) "PÅ" else "AV"}"
        }
        val ipField = EditText(this).apply {
            hint = "Optans adress"
            setText(currentIp ?: prefs.getString("ip", null) ?: FALLBACK_IP)
            setSingleLine(true)
        }
        val diag = TextView(this).apply {
            text = Diag.text().ifEmpty { "(tom logg)" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            addView(diag)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (220 * d).toInt())
        }
        box.addView(info)
        box.addView(ipField)
        box.addView(TextView(this).apply { text = "Diagnoslogg"; setPadding(0, (8 * d).toInt(), 0, 0) })
        box.addView(scroll)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Katapult Opta HMI")
            .setView(box)
            .setPositiveButton("Använd adressen") { _, _ ->
                val ip = ipField.text.toString().trim()
                if (ip.isNotEmpty()) {
                    showSplash("Ansluter till $ip…")
                    bindTo(null, "adress inmatad")
                    testOpta(ip)
                    load(ip)
                }
            }
            .setNeutralButton("Sök igen") { _, _ -> showSplash("Söker Optan…"); connect() }
            .setNegativeButton(if (prefs.getBoolean("kiosk", false)) "Kiosk av" else "Kiosk på") { _, _ -> toggleKiosk() }
            .create()
        dlg.show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
        ui.removeCallbacksAndMessages(null)
        try { server.stop() } catch (_: Exception) {}
        try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
