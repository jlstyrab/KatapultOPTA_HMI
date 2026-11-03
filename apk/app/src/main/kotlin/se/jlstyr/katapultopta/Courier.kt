package se.jlstyr.katapultopta

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.CRC32

/**
 * Plattan som bud (2026-11-03): "Skicka till Optan" när Katapult inte når
 * Optan (kundens LAN, t.ex. Carlsberg bakom fabrikens switch). Katapult köar
 * paketet och svarar på nästa push med `"hmi":{id,device,size,crc,gzip,name,ts}`;
 * då hämtar plattan `GET /panel/hmi/{id}` (paneltoken), kontrollerar storlek +
 * CRC32 och laddar själv upp till Optan med samma följd som pollerns
 * push_opta_hmi: login (nivå 2) → hmi/begin → hmi/chunk à 4096 B → hmi/end →
 * logout. Kvittot `POST /panel/hmi/{id}/result {ok, error?, seq?}`.
 *
 * Teknikerlösenordet ligger krypterat på plattan (Secret) och skickas aldrig
 * till Katapult. Allt körs på rapportörens tråd, i följd efter pushen, och
 * driftkortet i WebView:n pausas under uppladdningen så runtimens pollning
 * inte tar Optans få sockets — sedan laddas kortet om och det nya syns.
 */
class Courier(ctx: Context, private val reporter: Reporter, private val screen: Screen) {
    /** Skärmen: pausa runtimen (visa en skylt) och ladda om driftkortet. Anropas från budets tråd. */
    interface Screen {
        fun pause(text: String)
        fun resume()
    }

    private val prefs: SharedPreferences = ctx.getSharedPreferences("opta", Context.MODE_PRIVATE)
    /** Senaste utfall, visas i inställningsdialogen. */
    @Volatile var lastResult: String = "inget paket mottaget"

    companion object {
        const val PASS_KEY = "opta_pass"
        private const val CHUNK = 4096                // Optans hmiChunkBuf, samma som pollerns OPTA_HMI_CHUNK

        /** Katapults device_slug (routers/panels.py) — så matchas push-svarets
         *  `device` mot Optorna plattan rapporterar. */
        fun deviceSlug(s: String): String =
            s.lowercase().replace('å', 'a').replace('ä', 'a').replace('ö', 'o').replace('é', 'e')
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .trim { it == '-' || it == '_' }
    }

    fun password(): String = Secret.get(prefs, PASS_KEY)

    /** Ett paket ur push-svaret. Kvitterar alltid (utom när Katapult inte går att nå). */
    fun deliver(hmi: JSONObject) {
        val id = hmi.optLong("id", -1)
        if (id < 0) { log("FEL push-svarets hmi saknar id: ${hmi.toString().take(120)}"); return }
        val device = hmi.optString("device", "")
        log("paket $id (${hmi.optLong("size")} B, \"${hmi.optString("name")}\") till $device väntar")
        val r = try { install(id, hmi) } catch (e: Exception) { Result(false, "${e.javaClass.simpleName}: ${e.message}") }
        if (!r.ok) log("FEL paket $id: ${r.error}")
        acknowledge(id, r)
        lastResult = "${Diag.now()} paket $id → ${if (r.ok) "inlagt i $device${r.seq?.let { ", seq $it" } ?: ""}" else "FEL ${r.error}"}"
    }

    private class Result(val ok: Boolean, val error: String? = null, val seq: Int? = null)

    private fun install(id: Long, hmi: JSONObject): Result {
        val device = hmi.optString("device", "")
        val size = hmi.optLong("size", -1)
        val crc = hmi.optLong("crc", -1)
        val ip = reporter.reachableOptas().firstOrNull { (_, dev) -> deviceSlug(dev) == deviceSlug(device) }?.first
            ?: return Result(false, "plattan når ingen Opta \"$device\" just nu (har: " +
                reporter.reachableOptas().joinToString { deviceSlug(it.second) }.ifEmpty { "ingen" } + ")")
        val pass = password()
        if (pass.isEmpty()) return Result(false, "teknikerlösenord saknas på plattan")

        // 1. Hämta och kontrollera innan något går till Optan
        val (code, blob) = reporter.katapult("GET", "/panel/hmi/$id", null, null)
        if (code != 200) return Result(false, "hämtning gav HTTP $code ${String(blob, Charsets.UTF_8).take(120)}")
        val actual = CRC32().apply { update(blob) }.value
        if (blob.size.toLong() != size || actual != crc)
            return Result(false, "paketet stämmer inte: ${blob.size} B crc $actual, väntat $size B crc $crc")
        log("hämtat paket $id: ${blob.size} B, crc ok")

        // 2. Redan inlagt (kvittot försvann förra gången)? Då räcker ett nytt kvitto.
        val info = try { optaJson("GET", "http://$ip/api/hmi/info", null, null, 4).second } catch (_: Exception) { null }
        if (info != null && info.optBoolean("present") && info.optLong("crc", -1) == crc && info.optLong("bytes", -1) == size) {
            log("paket $id finns redan i Optan $ip (seq ${info.optInt("seq")}) — laddas inte upp igen")
            return Result(true, seq = info.optInt("seq"))
        }

        // 3. Ladda upp med runtimen pausad
        screen.pause("Tar emot nytt driftkort från Katapult…")
        try {
            Thread.sleep(2000)    // låt runtimens sista anrop mot Optan bli klara
            return upload(ip, pass, blob, hmi)
        } finally {
            screen.resume()
        }
    }

    private fun upload(ip: String, pass: String, blob: ByteArray, hmi: JSONObject): Result {
        val base = "http://$ip"
        val t0 = System.currentTimeMillis()
        // Inloggningen provas om bara vid nätfel: fel lösenord räknas mot Optans spärr.
        val (lc, lj) = optaJson("POST", "$base/api/login", form("pass" to pass), null, 4)
        val tok = lj.optString("token", "")
        if (lc != 200 || tok.isEmpty()) return Result(false, "inloggning på Optan misslyckades: ${lj.optString("err").ifEmpty { "HTTP $lc" }}")
        try {
            if (lj.optInt("level", 0) < 2) return Result(false, "lösenordet ger inte nivå 2 (Tekniker) — paketet kräver det")
            val q = "size=${blob.size}&crc=${CRC32().apply { update(blob) }.value}" +
                "&gzip=${if (hmi.optBoolean("gzip", true)) 1 else 0}" +
                "&name=${enc(hmi.optString("name").take(31))}&device=${enc(hmi.optString("device").take(31))}" +
                "&ts=${enc(hmi.optString("ts").take(23))}&token=${enc(tok)}"
            // begin raderar flashslottet — kan ta några sekunder
            val (bc, bj) = optaJson("POST", "$base/api/hmi/begin?$q", ByteArray(0), "application/x-www-form-urlencoded", 8)
            if (bc != 200) return Result(false, "begin: ${bj.optString("err").ifEmpty { "HTTP $bc" }}")
            var chunks = 0
            var off = 0
            while (off < blob.size) {
                val piece = blob.copyOfRange(off, minOf(off + CHUNK, blob.size))
                var (cc, cj) = optaJson("POST", "$base/api/hmi/chunk?off=$off&token=${enc(tok)}", piece, "application/octet-stream", 8)
                if (cc == 409 && cj.optLong("expected", -1) == (off + piece.size).toLong()) cc = 200   // gick in i ett tidigare försök
                if (cc != 200) return Result(false, "chunk $off: ${cj.optString("err").ifEmpty { "HTTP $cc" }}")
                off += piece.size; chunks++
            }
            val (ec, ej) = optaJson("POST", "$base/api/hmi/end", form("token" to tok), null, 8)
            if (ec != 200) return Result(false, "end: ${ej.optString("err").ifEmpty { "HTTP $ec" }}")
            val seq = ej.optJSONObject("hmi")?.let { if (it.has("seq")) it.optInt("seq") else null }
            log("uppladdat till Optan $ip: ${blob.size} B i $chunks bitar på ${(System.currentTimeMillis() - t0) / 100 / 10.0} s, seq ${seq ?: "?"}")
            return Result(true, seq = seq)
        } finally {
            try { optaJson("POST", "$base/api/logout", form("token" to tok), null, 2) } catch (_: Exception) {}
        }
    }

    private fun acknowledge(id: Long, r: Result) {
        val body = JSONObject().put("ok", r.ok)
        if (r.error != null) body.put("error", r.error.take(300))
        if (r.seq != null) body.put("seq", r.seq)
        for (attempt in 1..3) {
            try {
                val (code, text) = reporter.katapult("POST", "/panel/hmi/$id/result",
                    body.toString().toByteArray(Charsets.UTF_8), "application/json")
                if (code == 200) { log("kvitterat paket $id: ${if (r.ok) "ok" else "fel"}"); return }
                log("FEL kvitto paket $id: HTTP $code ${String(text, Charsets.UTF_8).take(120)}")
                if (code in 400..499) return     // servern vill inte ha det — omförsök hjälper inte
            } catch (e: Exception) {
                log("FEL kvitto paket $id: ${e.javaClass.simpleName}: ${e.message}")
            }
            Thread.sleep(3000L * attempt)
        }
        // Katapult lämnar ut paketet igen efter en stund; finns det då redan i Optan kvitteras det direkt.
    }

    // ── HTTP mot Optan (processens nät, bundet till kabeln när Optan sitter där) ──

    private fun form(vararg kv: Pair<String, String>): ByteArray =
        kv.joinToString("&") { (k, v) -> "$k=${enc(v)}" }.toByteArray(Charsets.UTF_8)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** (HTTP-kod, JSON). Omförsök bara på nätfel — Optans enda lediga socket ger ibland RST. */
    private fun optaJson(method: String, u: String, body: ByteArray?, type: String?, tries: Int): Pair<Int, JSONObject> {
        var last: Exception? = null
        for (i in 0 until tries) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 15_000
                c.requestMethod = method
                if (method == "POST") {
                    val b = body ?: ByteArray(0)
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", type ?: "application/x-www-form-urlencoded")
                    // Buffrat, inte strömmat (bitarna är ≤ 4 KB): Content-Length sätts ändå,
                    // och felsvarets text (t.ex. "fel lösenord" på 401) går att läsa.
                    c.outputStream.use { it.write(b) }
                }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                c.disconnect()
                val j = try { JSONObject(text.ifEmpty { "{}" }) } catch (_: Exception) { JSONObject().put("err", text.take(120)) }
                return code to j
            } catch (e: IOException) {
                last = e
                Thread.sleep(400L + 300L * i)
            }
        }
        throw IOException("${u.substringBefore('?')}: ${last?.javaClass?.simpleName}: ${last?.message}")
    }

    private fun log(msg: String) = Diag.log("bud: $msg")
}
