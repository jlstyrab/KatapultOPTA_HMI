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
 * nätverk Optan sitter på — det nätverket binds sedan processen till
 * (nätbindning, D15/B5): Android lägger annars standardrutten på det nät som
 * har internet (LTE/Wi-Fi), inte på kabeln till PLC:n.
 */
object Discovery {
    const val PORT = 5000
    private const val MAGIC = "KATAPULT_DISCOVER_v1"

    data class Hit(val ip: String, val name: String, val id: String, val network: Network?)

    /** Blockerar ~1,5 s per nätverk. Anropa från en bakgrundstråd. */
    fun find(ctx: Context, timeoutMs: Int = 1500): List<Hit> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hits = LinkedHashMap<String, Hit>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("katapult-discover")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { null }
        try {
            val nets = cm.allNetworks.filter { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@filter false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }.sortedBy { n ->
                if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) 0 else 1
            }
            for (n in nets) probe(cm, n, timeoutMs, hits)
            if (hits.isEmpty()) probe(cm, null, timeoutMs, hits)   // standardnätet, obundet
        } finally {
            try { lock?.release() } catch (_: Exception) {}
        }
        return hits.values.toList()
    }

    private fun probe(cm: ConnectivityManager, n: Network?, timeoutMs: Int, hits: MutableMap<String, Hit>) {
        val targets = ArrayList<InetAddress>()
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        if (n != null) {
            cm.getLinkProperties(n)?.linkAddresses?.forEach { la -> broadcastOf(la)?.let { targets.add(it) } }
        }
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.broadcast = true
                s.soTimeout = 300
                s.bind(InetSocketAddress(0))
                n?.bindSocket(s)
                val payload = MAGIC.toByteArray(Charsets.UTF_8)
                for (t in targets) {
                    try { s.send(DatagramPacket(payload, payload.size, t, PORT)) } catch (_: Exception) {}
                }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val text = String(p.data, 0, p.length, Charsets.UTF_8)
                        parse(text, p.address?.hostAddress ?: "", n)?.let { hits.putIfAbsent(it.ip, it) }
                    } catch (_: SocketTimeoutException) {
                        // fortsätt lyssna tills tidsgränsen
                    }
                }
            }
        } catch (_: Exception) {
            // nätverket försvann eller får inte sändas på — nästa
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
