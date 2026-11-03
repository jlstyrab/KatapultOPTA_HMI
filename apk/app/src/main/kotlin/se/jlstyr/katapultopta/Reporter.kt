package se.jlstyr.katapultopta

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Rapportören (E5, D16): var N:e minut ett gzippat JSON-paket till Katapults
 * `POST /panel/push` — plattans hälsa (batteri, laddare, nät, modell) och
 * varje Optas råa JSON (status, fb, service, modbus-map, curves, channels,
 * hmi/info). Katapult döper om till taggar och publicerar som en enhet;
 * tystnad ger offline-larm där, så pushen går även när Optan är onåbar
 * (ok:false) — då kan Katapult skilja "plattan död" från "Optan död".
 *
 * Nätval: anropet provas via det nät vars subnät innehåller Katapult-adressen
 * (LAN-fallet, Värpinge), sedan nät med internet (validerade först), sedan
 * processens standardnät (som kan vara bundet till Optans kabel). Svaret bär
 * next_minutes så servern styr takten, och ibland ett driftkortspaket som
 * budet (Courier) lägger in i Optan direkt efter pushen, på samma tråd.
 *
 * Larmvakten (2026-11-03): ändras Optans antal aktiva A- eller B-larm
 * (`alarm_a`/`alarm_b` i /api/status) pushas direkt, högst en extra push per
 * 30 s. Värdena kommer i första hand ur runtimens egen statuspollning (var
 * 3:e s, via JS-bryggan i MainActivity — inga egna anrop mot Optan); är
 * runtimen tyst i 25 s läser vakten själv.
 */
class Reporter(private val ctx: Context, private val ui: Handler) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("opta", Context.MODE_PRIVATE)
    /** Senaste utfall, visas i inställningsdialogen. */
    @Volatile var lastResult: String = "aldrig skickat"
    @Volatile private var running = false
    private var optas: () -> List<Pair<String, String>> = { emptyList() }   // (ip, reservnamn)
    private var retried = false
    /** Budet för driftkortspaket ur push-svaret; sätts av MainActivity. */
    var courier: Courier? = null
    /** (ip, device) för Optorna som svarade i senaste pushen — budets adressbok. */
    @Volatile private var reachable: List<Pair<String, String>> = emptyList()
    private val tick = object : Runnable {
        override fun run() { sendAsync(null); schedule(minutes()) }
    }

    // Larmvakten — allt nedan på ui-tråden.
    private val alarmSeen = HashMap<String, Pair<Int, Int>>()     // ip → (A, B)
    private val alarmSeenAt = HashMap<String, Long>()             // ip → senaste avläsning (elapsedRealtime)
    private var lastAlarmPush = -ALARM_GAP_MS
    private var alarmPushPending = false
    private val alarmPush = object : Runnable {
        override fun run() {
            alarmPushPending = false
            if (running) { alarmPushPending = true; ui.postDelayed(this, 5000); return }   // vänta ut pågående push
            lastAlarmPush = SystemClock.elapsedRealtime()
            sendAsync(null)
        }
    }
    private val alarmWatch = object : Runnable {
        override fun run() { readAlarmsIfQuiet(); ui.postDelayed(this, 15_000) }
    }

    companion object {
        private const val ALARM_GAP_MS = 30_000L      // högst en extra push per 30 s
        private const val ALARM_QUIET_MS = 25_000L    // runtimen tyst så länge → vakten läser själv
    }

    val enabled: Boolean get() = url().isNotEmpty() && token().isNotEmpty()
    fun url(): String = (prefs.getString("kat_url", "") ?: "").trim().trimEnd('/')
    fun token(): String = (prefs.getString("kat_token", "") ?: "").trim()
    fun minutes(): Int = prefs.getInt("kat_minutes", 10).coerceIn(1, 1440)

    /** Starta/starta om timern. `optas` ger adresserna att läsa vid varje tick. */
    fun start(optas: () -> List<Pair<String, String>>) {
        this.optas = optas
        restart()
    }

    fun restart() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(alarmWatch)
        if (!enabled) { Diag.log("rapportör: av (ingen Katapult-URL/token)"); return }
        Diag.log("rapportör: var ${minutes()}:e min → ${url()}")
        schedule(0.5)     // första rapporten 30 s efter start
        ui.postDelayed(alarmWatch, 15_000)
    }

    // ── Larmvakten ─────────────────────────────────────────────────────────

    /** En avläsning av Optans larmräknare (-1 = fältet saknas). Från vilken tråd som helst.
     *  `pushing` = avläsningen hör till en push som redan är på väg — bara ny baslinje. */
    fun observeAlarms(ip: String, a: Int, b: Int, pushing: Boolean = false) {
        if (a < 0 && b < 0) return                    // äldre firmware utan alarm_a/alarm_b
        ui.post {
            if (optas().none { it.first == ip }) return@post
            alarmSeenAt[ip] = SystemClock.elapsedRealtime()
            val prev = alarmSeen.put(ip, a to b)
            if (prev == null || pushing || prev == (a to b) || !enabled) return@post
            val what = listOfNotNull(
                if (prev.first != a) "A ${prev.first}→$a" else null,
                if (prev.second != b) "B ${prev.second}→$b" else null).joinToString(", ")
            val wait = lastAlarmPush + ALARM_GAP_MS - SystemClock.elapsedRealtime()
            when {
                alarmPushPending -> Diag.log("larmändring $what, push redan på väg")
                wait <= 0 -> { Diag.log("larmändring $what, pushar"); alarmPush.run() }
                else -> {
                    Diag.log("larmändring $what, pushar om ${(wait + 999) / 1000} s (spärr 30 s)")
                    alarmPushPending = true
                    ui.postDelayed(alarmPush, wait)
                }
            }
        }
    }

    /** Reserv när runtimen inte pollar (skylt, sidfel, äldre runtime): en lätt egen läsning. */
    private fun readAlarmsIfQuiet() {
        if (running) return
        val now = SystemClock.elapsedRealtime()
        for ((ip, _) in optas()) {
            if (now - (alarmSeenAt[ip] ?: 0L) < ALARM_QUIET_MS) continue
            Thread({
                try {
                    val c = URL("http://$ip/api/status").openConnection() as HttpURLConnection
                    c.connectTimeout = 3000; c.readTimeout = 4000
                    val text = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                    c.disconnect()
                    val j = JSONObject(text)
                    observeAlarms(ip, j.optInt("alarm_a", -1), j.optInt("alarm_b", -1))
                } catch (_: Exception) { }   // onåbar Opta syns i nästa push (ok:false)
            }, "alarm-watch").start()
        }
    }

    private fun schedule(minutes: Number) {
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, (minutes.toDouble() * 60_000).toLong())
    }

    fun sendAsync(done: ((String) -> Unit)?) {
        if (!enabled) { done?.invoke("ingen Katapult-URL/token"); return }
        if (running) { done?.invoke("en rapport pågår redan"); return }
        running = true
        Thread({
            var hmi: JSONObject? = null
            try {
                val r = try { sendOnce { hmi = it } } catch (e: Exception) { "FEL ${e.javaClass.simpleName}: ${e.message}" }
                lastResult = "${Diag.now()} $r"
                Diag.log("rapportör: $r")
                // Ett misslyckande → ett snabbt omförsök efter 2 min, sedan ordinarie takt.
                if (r.startsWith("FEL") && !retried) { retried = true; ui.post { schedule(2) } } else retried = false
                if (done != null) ui.post { done(r) }
                // Ett väntande driftkortspaket: i följd efter pushen, aldrig parallellt med nästa.
                hmi?.let { p ->
                    val c = courier
                    if (c == null) Diag.log("bud: paket i push-svaret men inget bud") else
                        try { c.deliver(p) } catch (e: Exception) { Diag.log("bud: FEL ${e.javaClass.simpleName}: ${e.message}") }
                }
            } finally { running = false }
        }, "reporter").start()
    }

    // ── Paketet ────────────────────────────────────────────────────────────

    /** Optorna plattan nådde i senaste pushen, som (ip, device). */
    fun reachableOptas(): List<Pair<String, String>> = reachable

    private fun sendOnce(onHmi: (JSONObject) -> Unit): String {
        val body = JSONObject()
        body.put("app", JSONObject().put("version", versionName()))
        body.put("tablet", tablet())
        val arr = JSONArray()
        for ((ip, name) in optas()) arr.put(collectOpta(ip, name))
        body.put("optas", arr)
        reachable = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optBoolean("ok") }.map { it.getString("ip") to it.optString("device") }
        val raw = body.toString().toByteArray(Charsets.UTF_8)
        val gz = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(raw) } }.toByteArray()

        val target = URL(url() + "/panel/push")
        var lastErr = "inget nätverk att skicka över"
        for (n in candidateNetworks(target.host)) {
            try {
                val c = (if (n == null) target.openConnection() else n.openConnection(target)) as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer ${token()}")
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Content-Encoding", "gzip")
                c.setFixedLengthStreamingMode(gz.size)
                c.outputStream.use { it.write(gz) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                c.disconnect()
                if (code == 200) {
                    val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                    val next = j.optInt("next_minutes", 0)
                    if (next in 1..1440 && next != minutes()) {
                        prefs.edit().putInt("kat_minutes", next).apply()
                        ui.post { schedule(next) }
                    }
                    j.optJSONObject("hmi")?.let(onHmi)
                    return "ok ${raw.size} B (${gz.size} B gz), ${arr.length()} Opta → ${j.optInt("published", -1)} taggar, nästa om ${minutes()} min via ${describe(n)}"
                }
                // Servern nådd men sa nej (fel token, 4xx/5xx): inte nätets fel.
                return "FEL HTTP $code ${text.take(140)} (via ${describe(n)})"
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message} (via ${describe(n)})"
                Diag.log("rapportör: $lastErr")
            }
        }
        return "FEL $lastErr"
    }

    /** Ett anrop till Katapult (paneltoken, samma nätval som pushen) → (HTTP-kod, svarets bytes).
     *  Kastar bara när inget nät nådde servern. */
    fun katapult(method: String, path: String, body: ByteArray?, type: String?): Pair<Int, ByteArray> {
        val target = URL(url() + path)
        var lastErr = "inget nätverk att nå Katapult över"
        for (n in candidateNetworks(target.host)) {
            try {
                val c = (if (n == null) target.openConnection() else n.openConnection(target)) as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 30_000
                c.requestMethod = method
                c.setRequestProperty("Authorization", "Bearer ${token()}")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", type ?: "application/octet-stream")
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                c.disconnect()
                return code to bytes
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message} (via ${describe(n)})"
                Diag.log("bud: $method $path: $lastErr")
            }
        }
        throw java.io.IOException(lastErr)
    }

    private fun versionName(): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun tablet(): JSONObject {
        val o = JSONObject()
        try {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) o.put("battery", level * 100 / scale)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                // "laddar" = laddaren sitter i (även fullt batteri) — larmet gäller laddaren, inte cellen
                o.put("charging", plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
        } catch (e: Exception) { Diag.log("batteri: ${e.message}") }
        o.put("network", networkType())
        o.put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        o.put("android", Build.VERSION.RELEASE ?: "")
        o.put("uptime_s", SystemClock.elapsedRealtime() / 1000)
        return o
    }

    private fun networkType(): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    /** Optans råa JSON. /api/status krävs (annars ok:false), resten är bäst
     *  möjliga — Optans enda lediga socket ger ibland RST, och ett saknat
     *  delfält ska inte larma "onåbar". */
    private fun collectOpta(ip: String, fallbackName: String): JSONObject {
        val o = JSONObject().put("ip", ip)
        try {
            o.put("status", JSONTokener(get("http://$ip/api/status")).nextValue())
            o.optJSONObject("status")?.let { observeAlarms(ip, it.optInt("alarm_a", -1), it.optInt("alarm_b", -1), pushing = true) }
        } catch (e: Exception) {
            return o.put("ok", false).put("err", "${e.javaClass.simpleName}: ${e.message}")
        }
        for ((key, path) in listOf("fb" to "fb", "service" to "service", "map" to "modbus-map",
                                   "curves" to "curves", "channels" to "channels", "info" to "hmi/info")) {
            try { o.put(key, JSONTokener(get("http://$ip/api/$path")).nextValue()) }
            catch (e: Exception) { Diag.log("rapportör: $ip /api/$path: ${e.message}") }
        }
        val dev = o.optJSONObject("info")?.optString("device", "") ?: ""
        o.put("device", if (dev.isNotEmpty()) dev else fallbackName)
        return o.put("ok", true)
    }

    private fun get(u: String): String {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 4000
                val code = c.responseCode
                val text = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                c.disconnect()
                if (code == 200) return text
                throw IllegalStateException("HTTP $code")
            } catch (e: Exception) {
                last = e
                Thread.sleep(300L * attempt)
            }
        }
        throw last ?: IllegalStateException("okänt fel")
    }

    // ── Nätval ─────────────────────────────────────────────────────────────

    private fun candidateNetworks(host: String): List<Network?> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val addr = try { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } as? Inet4Address } catch (_: Exception) { null }
        val onSubnet = ArrayList<Network>(); val internet = ArrayList<Network>(); val rest = ArrayList<Network>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            val lp = cm.getLinkProperties(n)
            val same = addr != null && lp?.linkAddresses?.any { la ->
                val a = la.address
                a is Inet4Address && sameSubnet(a, la.prefixLength, addr)
            } == true
            when {
                same -> onSubnet.add(n)
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> internet.add(n)
                else -> rest.add(n)
            }
        }
        internet.sortBy { n -> if (cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 0 else 1 }
        val out = ArrayList<Network?>()
        out.addAll(onSubnet); out.addAll(internet); out.add(null); out.addAll(rest)
        return out
    }

    private fun sameSubnet(a: Inet4Address, prefix: Int, b: Inet4Address): Boolean {
        if (prefix <= 0 || prefix > 32) return false
        val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    private fun toInt(a: Inet4Address): Int {
        val b = a.address
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun describe(n: Network?): String {
        if (n == null) return "standardnätet"
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(n)
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "LTE"
            else -> "nät $n"
        }
    }
}
