package com.sailer.browser.dns

import com.sailer.browser.download.HttpClients
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Pequeño "proxy" dentro de la app para que las páginas web también usen DNS
 * cifrado. WebView no permite cambiar sus DNS, pero sí usar un proxy: le
 * decimos que pase por este, que resuelve cada dirección con DNS over HTTPS
 * y luego simplemente reenvía la conexión cifrada (HTTPS) sin poder leerla.
 * Solo escucha en el propio móvil (127.0.0.1).
 */
object LocalDohProxy {

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "doh-proxy").apply { isDaemon = true } }
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private var server: ServerSocket? = null

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(name = "doh-proxy-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                pool.execute { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val requestLine = readLine(input) ?: return
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        // Solo túneles HTTPS (CONNECT): las conexiones sin cifrar ya están prohibidas.
        if (parts.size < 2 || parts[0] != "CONNECT") {
            respond(out, "405 Method Not Allowed")
            return
        }
        val (host, port) = parseTarget(parts[1]) ?: run { respond(out, "400 Bad Request"); return }
        val addresses = runCatching { resolve(host) }.getOrNull()
            ?.filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.sortedBy { if (it is Inet4Address) 0 else 1 }
        if (addresses.isNullOrEmpty()) {
            respond(out, "502 Bad Gateway")
            return
        }
        var upstream: Socket? = null
        for (address in addresses) {
            val candidate = Socket()
            try {
                candidate.connect(InetSocketAddress(address, port), 10_000)
                upstream = candidate
                break
            } catch (e: Exception) {
                runCatching { candidate.close() }
            }
        }
        val remote = upstream ?: run { respond(out, "504 Gateway Timeout"); return }
        remote.use {
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            client.soTimeout = 0
            pool.execute {
                pipe(remote.getInputStream(), out)
                runCatching { client.shutdownOutput() }
            }
            pipe(input, remote.getOutputStream())
            runCatching { remote.shutdownOutput() }
        }
    }

    private fun resolve(host: String): List<InetAddress> {
        if (isIpLiteral(host)) return listOf(InetAddress.getByName(host))
        val now = System.currentTimeMillis()
        cache[host]?.let { (time, list) -> if (now - time < 300_000) return list }
        val list = HttpClients.dohDns.lookup(host)
        cache[host] = now to list
        return list
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))

    private fun parseTarget(target: String): Pair<String, Int>? {
        val host: String
        val portText: String
        if (target.startsWith("[")) {
            host = target.substringAfter('[').substringBefore(']')
            portText = target.substringAfter("]:", "443")
        } else {
            host = target.substringBeforeLast(':')
            portText = target.substringAfterLast(':', "443")
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isEmpty()) return null
        return host.lowercase() to port
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        runCatching {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        }
    }

    private fun respond(out: OutputStream, status: String) {
        runCatching {
            out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }
}
