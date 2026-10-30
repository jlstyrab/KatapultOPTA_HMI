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
import android.text.InputType
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
 * nätverket (Wi-Fi är redan standardrutt).
 *
 * Katapult-läge (K1): samma skal visar i stället en Katapult-nods driftkort
 * (adressen till scada-sidan) — för anläggningar utan Opta (Värpinge).
 *
 * Inställningar + diagnoslogg: håll volym ned i 3 s och ange pin (K2,
 * standard 9999). Kiosk: app-fästning, skärmen alltid på, HOME-kategori.
 * Rapportören (E5) pushar hälsa + Optans värden till Katapult, se Reporter.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var overlay: TextView
    private lateinit var server: AssetServer
    private lateinit var prefs: SharedPreferences
    private lateinit var reporter: Reporter
    private val ui = Handler(Looper.getMainLooper())
    private var volDownSince = 0L
    private var currentIp: String? = null
    private var currentName: String? = null
    private var boundTo: String = "ingen"
    private val holdCheck = Runnable {
        if (volDownSince != 0L && System.currentTimeMillis() - volDownSince >= HOLD_MS) {
            volDownSince = 0L
            askPin { openSettings(null) }
        }
    }
    private val retry = Runnable { web.reload() }

    companion object {
        private const val HOLD_MS = 3000L
        private const val FALLBACK_IP = "192.168.200.1"   // Optans passiva fallback utan DHCP
        private const val DEFAULT_PIN = "9999"
        private const val MODE_OPTA = "opta"
        private const val MODE_KATAPULT = "katapult"
    }

    private fun mode(): String = prefs.getString("mode", MODE_OPTA) ?: MODE_OPTA
    private fun pin(): String = prefs.getString("pin", DEFAULT_PIN) ?: DEFAULT_PIN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("opta", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Diag.log("start, version ${versionName()}, läge ${mode()}")

        server = AssetServer(assets).also { it.start() }
        reporter = Reporter(this, ui)

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
                showOverlay("Kunde inte ladda $msg\n\nFörsöker igen om 10 s. Volym ned 3 s = inställningar.\n\n" + Diag.tail(8))
                ui.removeCallbacks(retry)
                ui.postDelayed(retry, 10000)
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("http://127.0.0.1") || mode() == MODE_KATAPULT) overlay.visibility = View.GONE
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
        // Rapportören läser Optan bara i Opta-läget; i Katapult-läget bara plattans hälsa.
        reporter.start {
            val ip = currentIp
            if (mode() == MODE_OPTA && ip != null) listOf(ip to (currentName ?: "opta")) else emptyList()
        }
        if (mode() == MODE_KATAPULT) loadKatapult() else { showSplash("Söker Optan…"); connect() }
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
                    chosen != null -> { currentName = chosen.name; bindFor(chosen); load(chosen.ip) }
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

    /** K1: Katapult-nodens egna driftkort i samma skal. Ingen Opta, ingen bindning. */
    private fun loadKatapult() {
        val url = (prefs.getString("kat_scada_url", "") ?: "").trim()
        currentIp = null
        bindTo(null, "Katapult-läge")
        if (url.isEmpty()) { openSettings("Katapult-läge: ange adressen till nodens driftkort."); return }
        Diag.log("laddar Katapult $url")
        overlay.visibility = View.GONE
        web.loadUrl(url)
    }

    private fun chooseAmong(hits: List<Discovery.Hit>) {
        val names = hits.map { "${it.name} · ${it.ip}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Flera Optor hittades")
            .setItems(names) { _, i -> currentName = hits[i].name; bindFor(hits[i]); load(hits[i].ip) }
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

    // ── Pin (K2) ───────────────────────────────────────────────────────────

    private fun askPin(then: () -> Unit) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Pin"
        }
        AlertDialog.Builder(this)
            .setTitle("Inställningar")
            .setMessage("Ange pin")
            .setView(field)
            .setPositiveButton("OK") { _, _ ->
                if (field.text.toString() == pin()) then()
                else { Diag.log("fel pin"); Toast.makeText(this, "Fel pin", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Avbryt", null)
            .show()
        field.requestFocus()
    }

    // ── Inställningar + diagnos (volym ned 3 s + pin) ──────────────────────

    private fun openSettings(note: String?) {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        // Färger (Johan 2026-10-30: grå text mot svart syntes dåligt): det som
        // GÄLLER (ifyllda värden, sparat läge) är rött, rubriker och hjälptext
        // vita. Bakgrunden sätts själv så färgerna håller oavsett
        // plattans dialogtema.
        val cText = Color.WHITE
        val cHelp = Color.parseColor("#e6edf3")
        val cValue = Color.parseColor("#ff6b6b")
        fun heading(t: String) = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(cText); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (18 * d).toInt(), 0, (2 * d).toInt())
        }
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(cText); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        fun help(t: String) = TextView(this).apply { text = t; textSize = 12f; setTextColor(cHelp) }
        fun value(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(cValue) }
        /** Inmatningsfält: värdet rött; ledtexten (syns bara när fältet är
         *  tomt) vit, så en tom ruta inte ser ifylld ut. */
        fun input(v: String, hintText: String) = EditText(this).apply {
            setText(v); hint = hintText; setSingleLine(true); textSize = 15f
            setTextColor(cValue); setHintTextColor(cHelp)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0)
            setBackgroundColor(Color.parseColor("#161b22"))
        }

        // Läge
        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbOpta = RadioButton(this).apply { text = "Opta-HMI"; id = 1; setTextColor(cText) }
        val rbKat = RadioButton(this).apply { text = "Katapult-driftkort"; id = 2; setTextColor(cText) }
        modes.addView(rbOpta); modes.addView(rbKat)
        modes.check(if (mode() == MODE_KATAPULT) 2 else 1)

        val ipField = input(currentIp ?: prefs.getString("ip", null) ?: FALLBACK_IP, "t.ex. 192.168.1.117")
        val scadaField = input(prefs.getString("kat_scada_url", "") ?: "", "används inte i Opta-HMI")

        // Rapportören
        val urlField = input(prefs.getString("kat_url", "") ?: "", "tomt = ingen rapport")
        val tokenField = input("", if (reporter.token().isNotEmpty()) "tomt = behåll, - = ta bort" else "klistra in token från Katapult")
        val minutesField = input(reporter.minutes().toString(), "10").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val pushStatus = help("Senast: ${reporter.lastResult}")
        val sendNow = Button(this).apply {
            text = "Skicka nu"
            setOnClickListener {
                // Spara fälten först så "Skicka nu" använder det som står i rutan.
                saveReporterFields(urlField, tokenField, minutesField)
                pushStatus.text = "skickar…"
                reporter.sendAsync { r -> pushStatus.text = "Senast: $r" }
            }
        }
        val pinField = input("", "tomt = behåll nuvarande").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        // Diagnos (längst ner — inget här ska skrivas ner eller ändras)
        val info = TextView(this).apply {
            text = "App: ${versionName()}
" +
                "Opta: ${currentIp ?: "—"}   Nätbindning: $boundTo
" +
                "Assetserver: 127.0.0.1:${server.port} (${server.requests} anrop)"
            textSize = 11f; setTextColor(cHelp); typeface = android.graphics.Typeface.MONOSPACE
        }
        val diag = TextView(this).apply {
            text = Diag.text().ifEmpty { "(tom logg)" }
            textSize = 11f
            setTextColor(cHelp)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val diagScroll = ScrollView(this).apply {
            addView(diag)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (180 * d).toInt())
        }

        if (note != null) box.addView(value(note))
        box.addView(help("Rött = det som gäller nu. Vit text i en ruta är bara en ledtext — rutan är tom."))

        box.addView(heading("Läge"))
        box.addView(help("Opta-HMI: driftkortet ur Optan på nätet. Katapult-driftkort: en Katapult-nods kort i stället."))
        box.addView(modes)
        box.addView(value(if (mode() == MODE_KATAPULT) "Gäller: Katapult-driftkort" else "Gäller: Opta-HMI"))
        box.addView(value("Kiosk (fäst app): ${if (prefs.getBoolean("kiosk", false)) "PÅ" else "AV"} — knappen Kiosk längst ner"))

        box.addView(label("Optans adress"))
        box.addView(help("PLC:ns IP-adress. \"Sök Opta\" längst ner hittar den automatiskt. Direktkabel utan router: 192.168.200.1."))
        box.addView(ipField)

        box.addView(label("Driftkortsadress (bara Katapult-läge)"))
        box.addView(help("Nodens driftkort, t.ex. https://nod/scada. Behövs inte i Opta-HMI."))
        box.addView(scadaField)

        box.addView(heading("Rapport till Katapult"))
        box.addView(help("Plattan skickar sin och Optans status till Katapult. Lämna Katapult-URL tom om ni inte använder det."))
        box.addView(label("Katapult-URL (API)"))
        box.addView(help("Nodens API, t.ex. http://192.168.1.178:8000"))
        box.addView(urlField)
        box.addView(label("Paneltoken"))
        box.addView(help("Skapas i Katapult under Enheter → Paneler och visas bara en gång där. Här visas den aldrig."))
        box.addView(value(if (reporter.token().isNotEmpty()) "Gäller: token sparad" else "Gäller: ingen token"))
        box.addView(tokenField)
        box.addView(label("Intervall (minuter)"))
        box.addView(minutesField)
        box.addView(pushStatus); box.addView(sendNow)

        box.addView(heading("Pin för den här dialogen"))
        box.addView(help("4–8 siffror."))
        box.addView(value(if (pin() == DEFAULT_PIN) "Gäller: standard ($DEFAULT_PIN) — byt!" else "Gäller: egen pin"))
        box.addView(pinField)

        box.addView(heading("Diagnos"))
        box.addView(help("Teknisk information för felsökning — inget här behöver skrivas ner."))
        box.addView(info)
        box.addView(label("Logg")); box.addView(diagScroll)
        val outer = ScrollView(this).apply { addView(box) }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Katapult Opta HMI")
            .setView(outer)
            .setPositiveButton("Spara") { _, _ ->
                val newMode = if (modes.checkedRadioButtonId == 2) MODE_KATAPULT else MODE_OPTA
                val modeChanged = newMode != mode()
                prefs.edit().putString("mode", newMode).putString("kat_scada_url", scadaField.text.toString().trim()).apply()
                saveReporterFields(urlField, tokenField, minutesField)
                val newPin = pinField.text.toString().trim()
                if (newPin.isNotEmpty()) {
                    if (newPin.length in 4..8) { prefs.edit().putString("pin", newPin).apply(); Diag.log("pin bytt"); Toast.makeText(this, "Pin bytt", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(this, "Pin: 4–8 siffror — inte bytt", Toast.LENGTH_LONG).show()
                }
                reporter.restart()
                if (newMode == MODE_KATAPULT) {
                    loadKatapult()
                } else {
                    val ip = ipField.text.toString().trim()
                    if (ip.isNotEmpty() && (ip != currentIp || modeChanged)) {
                        showSplash("Ansluter till $ip…")
                        bindTo(null, "adress inmatad")
                        testOpta(ip)
                        load(ip)
                    }
                }
            }
            .setNeutralButton("Sök Opta") { _, _ ->
                prefs.edit().putString("mode", MODE_OPTA).apply()
                showSplash("Söker Optan…"); connect()
            }
            .setNegativeButton(if (prefs.getBoolean("kiosk", false)) "Kiosk av" else "Kiosk på") { _, _ -> toggleKiosk() }
            .create()
        dlg.show()
        diagScroll.post { diagScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun saveReporterFields(urlField: EditText, tokenField: EditText, minutesField: EditText) {
        val e = prefs.edit()
        e.putString("kat_url", urlField.text.toString().trim())
        val t = tokenField.text.toString().trim()
        if (t == "-") e.putString("kat_token", "") else if (t.isNotEmpty()) e.putString("kat_token", t)
        minutesField.text.toString().trim().toIntOrNull()?.let { if (it in 1..1440) e.putInt("kat_minutes", it) }
        e.apply()
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
