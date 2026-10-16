package se.jlstyr.katapultopta

import android.content.res.AssetManager
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal HTTP-server på 127.0.0.1 som serverar runtimen ur assets/www.
 *
 * Varför: WebView:n får då en riktig http-origin (http://127.0.0.1:port), så
 * runtimens absoluta /assets-sökvägar fungerar, localStorage (styr-token)
 * består, och anrop mot Optan (http) är inte "blandat innehåll". Inga
 * avvecklade file://-flaggor, inget bibliotek. Bara GET, en anslutning per
 * tråd, stängs efter svar — det räcker för ett dussin filer vid start.
 */
class AssetServer(private val assets: AssetManager) {
    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    @Volatile private var running = true

    val port: Int get() = socket.localPort

    fun start() {
        Thread({ acceptLoop() }, "asset-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket.close() } catch (_: IOException) {}
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val client = socket.accept()
                Thread({ handle(client) }, "asset-http-conn").apply { isDaemon = true }.start()
            } catch (e: IOException) {
                if (running) Thread.sleep(50)
            }
        }
    }

    private fun handle(client: Socket) {
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
            } catch (e: IOException) {
                val msg = "Not found".toByteArray()
                out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${msg.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                out.write(msg)
            }
            out.flush()
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
