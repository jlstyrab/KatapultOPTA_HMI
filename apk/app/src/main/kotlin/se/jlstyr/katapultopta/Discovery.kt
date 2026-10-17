package se.jlstyr.katapultopta

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Katapults UDP-upptäckt (port 5000, "KATAPULT_DISCOVER_v1") — samma som
 * plattformens poller använder. Körs per nätverk (Ethernet först, sedan
 * Wi-Fi) med socketen bunden till nätverket, så svaret också säger VILKET
 * nätverk Optan sitter på. Allt som händer loggas till Diag, eftersom paddan
 * i fält saknar adb.
 */
object Discovery {
    const val PORT = 5000
    private const val MAGIC = "KATAPULT_DISCOVER_v1"

    data class Hit(val ip: String, val name: String, val id: String, val network: Network?)

    fun isEthernet(ctx: Context, n: Network?): Boolean {
        if (n == null) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }

    /** Blockerar ~1,5 s per nätverk. Anropa från en bakgrundstråd. */
    fun find(ctx: Context, timeoutMs: Int = 1500): List<Hit> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hits = LinkedHashMap<String, Hit>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("katapult-discover")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Diag.log("multicast-lås: ${e.message}"); null }
        try {
            val nets = cm.allNetworks.filter { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@filter false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }.sortedBy { n ->
                if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) 0 else 1
            }
            Diag.log("upptäckt: ${nets.size} nätverk (Ethernet/Wi-Fi) av ${cm.allNetworks.size}")
            for (n in nets) probe(cm, n, timeoutMs, hits)
            if (hits.isEmpty()) probe(cm, null, timeoutMs, hits)   // standardnätet, obundet
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
        Diag.log("upptäckt klar: ${hits.size} Opta(r)" + hits.values.joinToString("") { " ${it.name}@${it.ip}" })
        return hits.values.toList()
    }

    /** Fråga EN adress direkt (unicast) — "finns Optan på den adress jag skrev?" */
    fun ping(ip: String, timeoutMs: Int = 1500): Hit? {
        return try {
            DatagramSocket(null).use { s ->
                s.soTimeout = timeoutMs
                s.bind(InetSocketAddress(0))
                val payload = MAGIC.toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(ip), PORT))
                val buf = ByteArray(2048)
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val hit = parse(String(p.data, 0, p.length, Charsets.UTF_8), p.address?.hostAddress ?: ip, null)
                Diag.log("unicast $ip: ${if (hit != null) "svar från ${hit.name}" else "oläsbart svar"}")
                hit
            }
        } catch (e: SocketTimeoutException) {
            Diag.log("unicast $ip: inget svar på ${timeoutMs} ms"); null
        } catch (e: Exception) {
            Diag.log("unicast $ip: ${e.javaClass.simpleName} ${e.message}"); null
        }
    }

    private fun describe(cm: ConnectivityManager, n: Network): String {
        val caps = cm.getNetworkCapabilities(n)
        val kind = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            else -> "annat"
        }
        val addrs = cm.getLinkProperties(n)?.linkAddresses
            ?.filter { it.address is Inet4Address }
            ?.joinToString(",") { "${it.address.hostAddress}/${it.prefixLength}" } ?: "?"
        val inet = if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) "internet" else "utan internet"
        return "$kind $addrs ($inet)"
    }

    private fun probe(cm: ConnectivityManager, n: Network?, timeoutMs: Int, hits: MutableMap<String, Hit>) {
        val label = if (n == null) "obundet" else describe(cm, n)
        val targets = ArrayList<InetAddress>()
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        if (n != null) {
            cm.getLinkProperties(n)?.linkAddresses?.forEach { la -> broadcastOf(la)?.let { targets.add(it) } }
        }
        var replies = 0
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.broadcast = true
                s.soTimeout = 300
                s.bind(InetSocketAddress(0))
                if (n != null) n.bindSocket(s)
                val payload = MAGIC.toByteArray(Charsets.UTF_8)
                val sent = ArrayList<String>()
                for (t in targets) {
                    try { s.send(DatagramPacket(payload, payload.size, t, PORT)); sent.add(t.hostAddress ?: "?") }
                    catch (e: Exception) { Diag.log("  sänd till ${t.hostAddress}: ${e.javaClass.simpleName} ${e.message}") }
                }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        replies++
                        val text = String(p.data, 0, p.length, Charsets.UTF_8)
                        val hit = parse(text, p.address?.hostAddress ?: "", n)
                        if (hit != null) hits.putIfAbsent(hit.ip, hit)
                        else Diag.log("  oläsbart svar från ${p.address?.hostAddress}: ${text.take(60)}")
                    } catch (_: SocketTimeoutException) {
                        // fortsätt lyssna tills tidsgränsen
                    }
                }
                Diag.log("nät $label: sänt till ${sent.joinToString(",")}, $replies svar")
            }
        } catch (e: Exception) {
            Diag.log("nät $label: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun parse(json: String, from: String, n: Network?): Hit? {
        return try {
            val o = JSONObject(json)
            val ip = o.optString("ip", from)
            if (ip.isEmpty()) null else Hit(ip, o.optString("name", ip), o.optString("id", ""), n)
        } catch (_: Exception) { null }
    }

    /** Subnätets broadcastadress ur adress + prefixlängd (bara IPv4). */
    private fun broadcastOf(la: LinkAddress): InetAddress? {
        val a = la.address as? Inet4Address ?: return null
        val bytes = a.address
        val prefix = la.prefixLength
        if (prefix <= 0 || prefix >= 32) return null
        var v = 0L
        for (b in bytes) v = (v shl 8) or (b.toLong() and 0xFF)
        val hostMask = (1L shl (32 - prefix)) - 1
        val bc = v or hostMask
        val out = ByteArray(4) { i -> ((bc shr (8 * (3 - i))) and 0xFF).toByte() }
        return try { InetAddress.getByAddress(out) } catch (_: Exception) { null }
    }
}
