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
 * next_minutes så servern styr takten.
 */
class Reporter(private val ctx: Context, private val ui: Handler) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("opta", Context.MODE_PRIVATE)
    /** Senaste utfall, visas i inställningsdialogen. */
    @Volatile var lastResult: String = "aldrig skickat"
    @Volatile private var running = false
    private var optas: () -> List<Pair<String, String>> = { emptyList() }   // (ip, reservnamn)
    private var retried = false
    private val tick = object : Runnable {
        override fun run() { sendAsync(null); schedule(minutes()) }
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
        if (!enabled) { Diag.log("rapportör: av (ingen Katapult-URL/token)"); return }
        Diag.log("rapportör: var ${minutes()}:e min → ${url()}")
        schedule(0.5)     // första rapporten 30 s efter start
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
            val r = try { sendOnce() } catch (e: Exception) { "FEL ${e.javaClass.simpleName}: ${e.message}" } finally { running = false }
            lastResult = "${Diag.now()} $r"
            Diag.log("rapportör: $r")
            // Ett misslyckande → ett snabbt omförsök efter 2 min, sedan ordinarie takt.
            if (r.startsWith("FEL") && !retried) { retried = true; ui.post { schedule(2) } } else retried = false
            if (done != null) ui.post { done(r) }
        }, "reporter").start()
    }

    // ── Paketet ────────────────────────────────────────────────────────────

    private fun sendOnce(): String {
        val body = JSONObject()
        body.put("app", JSONObject().put("version", versionName()))
        body.put("tablet", tablet())
        val arr = JSONArray()
        for ((ip, name) in optas()) arr.put(collectOpta(ip, name))
        body.put("optas", arr)
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
