package se.jlstyr.katapultopta

import android.content.res.AssetManager
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Minimal HTTP-server på 127.0.0.1 som serverar runtimen ur assets/www.
 *
 * Varför: WebView:n får då en riktig http-origin (http://127.0.0.1:port), så
 * runtimens absoluta /assets-sökvägar fungerar, localStorage (styr-token)
 * består, och anrop mot Optan (http) är inte "blandat innehåll". Inga
 * avvecklade file://-flaggor, inget bibliotek. Bara GET, en anslutning per
 * tråd, stängs efter svar.
 *
 * Binder EXPLICIT till IPv4 127.0.0.1 (inte getLoopbackAddress, som kan bli
 * ::1) så adressen WebView:n får stämmer. Varje anslutning är helt
 * inkapslad i try/catch: ett ohanterat undantag i en tråd fäller hela
 * Android-processen, och Chrome öppnar gärna "spekulativa" anslutningar som
 * aldrig skickar något (→ tidsgräns i readLine).
 */
class AssetServer(private val assets: AssetManager) {
    private val socket = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    @Volatile private var running = true
    @Volatile var requests = 0
        private set

    val port: Int get() = socket.localPort

    fun start() {
        Thread({ acceptLoop() }, "asset-http").apply { isDaemon = true }.start()
        Diag.log("assetserver lyssnar på 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        try { socket.close() } catch (_: IOException) {}
    }

    /** Självtest från appen själv (kör på bakgrundstråd): når vi vår egen server? */
    fun selfTest(): String {
        return try {
            val c = URL("http://127.0.0.1:$port/index.html").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 3000
            val code = c.responseCode
            val len = c.inputStream.use { it.readBytes().size }
            c.disconnect()
            "HTTP $code, $len B"
        } catch (e: Exception) {
            "FEL ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = socket.accept()
                Thread({ handle(client) }, "asset-http-conn").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (running) {
                    Diag.log("assetserver accept: ${e.javaClass.simpleName} ${e.message}")
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val h = reader.readLine()
                    if (h == null || h.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val target = if (parts.size >= 2) parts[1] else "/"
                var path = target.substringBefore('?').substringBefore('#')
                if (path.isEmpty() || path == "/") path = "/index.html"
                if (path.contains("..")) path = "/index.html"
                val out = BufferedOutputStream(c.getOutputStream())
                try {
                    val bytes = assets.open("www$path").use { it.readBytes() }
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${mime(path)}\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    out.write(bytes)
                    if (requests < 30) Diag.log("GET $path → 200 (${bytes.size} B)")
                } catch (e: IOException) {
                    val msg = "Not found".toByteArray()
                    out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${msg.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    out.write(msg)
                    if (requests < 30) Diag.log("GET $path → 404")
                }
                requests++
                out.flush()
            }
        } catch (e: Exception) {
            // Tidsgräns på en tom (spekulativ) anslutning är normalt — logga bara
            // sådant som inte är det, och låt aldrig tråden dö med undantag.
            if (e !is java.net.SocketTimeoutException) Diag.log("assetserver: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ico" -> "image/x-icon"
        "txt", "yaml", "yml" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }
}
