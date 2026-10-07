package io.github.micro123.mediaplayer

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Loopback-only fixture. No user files or external network service is involved. */
class LocalMediaHttpServer(private val files: Map<String, Pair<String, ByteArray>>) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = Executors.newFixedThreadPool(4)
    val base = "http://127.0.0.1:${server.localPort}"
    @Volatile private var running = true
    private val accept = Thread {
        while (running) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            clients.execute {
                socket.use {
                    try {
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val first = reader.readLine().orEmpty()
                        val path = first.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
                        var range: String? = null
                        for (header in 0 until 50) {
                            val line = reader.readLine().orEmpty()
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                        }
                        val value = files[path]
                        val out = socket.getOutputStream()
                        if (value == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@execute }
                        val bytes = value.second
                        val bounds = range?.removePrefix("bytes=")?.split('-')
                        val start = bounds?.getOrNull(0)?.toIntOrNull()?.coerceIn(0, bytes.lastIndex) ?: 0
                        val end = bounds?.getOrNull(1)?.toIntOrNull()?.coerceIn(start, bytes.lastIndex) ?: bytes.lastIndex
                        val response = buildString {
                            append(if (range == null) "HTTP/1.1 200 OK\r\n" else "HTTP/1.1 206 Partial Content\r\n")
                            append("Content-Type: ${value.first}\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                            if (range != null) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                            append("\r\n")
                        }
                        out.write(response.toByteArray())
                        if (!first.startsWith("HEAD ")) out.write(bytes, start, end - start + 1)
                        out.flush()
                    } catch (_: Exception) { /* Playback can close an in-flight range request. */ }
                }
            }
        }
    }.apply { isDaemon = true; start() }
    override fun close() { running = false; server.close(); clients.shutdownNow(); accept.join(1000) }
}
