package com.example.cellprobe.netperf

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Every probe takes a `Network` and talks ONLY through it. That is what makes
// per-SIM measurement possible without touching process-wide routing.
// All probes BLOCK, so call them from a worker thread, never the main thread.

/* ======================================================================
 * 1. DNS  -  "how long does the network's resolver take to answer?"
 * ====================================================================== */
object DnsProbe {
    // Bypass the on-device cache in both directions: don't read it, don't fill it.
    private const val FLAGS = DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE

    val DEFAULT_DOMAINS = listOf(
        "google.com", "cloudflare.com", "wikipedia.org", "amazon.com", "microsoft.com"
    )

    fun run(
        network: Network,
        domains: List<String> = DEFAULT_DOMAINS,
        repeats: Int = 2,
        timeoutMs: Long = 3000
    ): DnsMetrics {
        val executor = Executors.newSingleThreadExecutor()
        val rtts = ArrayList<Double>()
        var failures = 0
        var attempts = 0
        try {
            repeat(repeats) {
                for (d in domains) {
                    attempts++
                    val ms = queryOnce(network, d, executor, timeoutMs)
                    if (ms == null) failures++ else rtts.add(ms)
                }
            }
        } finally {
            executor.shutdown()
        }
        return DnsMetrics(attempts, failures, Stats.summarize(rtts))
    }

    private fun queryOnce(
        network: Network,
        domain: String,
        executor: java.util.concurrent.Executor,
        timeoutMs: Long
    ): Double? {
        val latch = CountDownLatch(1)
        val endNs = AtomicLong(0)
        val gotAnswer = AtomicBoolean(false)
        val cancel = CancellationSignal()

        val startNs = System.nanoTime()
        DnsResolver.getInstance().query(
            network, domain, DnsResolver.TYPE_A, FLAGS, executor, cancel,
            object : DnsResolver.Callback<MutableList<InetAddress>> {
                override fun onAnswer(answer: MutableList<InetAddress>, rcode: Int) {
                    endNs.set(System.nanoTime())
                    gotAnswer.set(answer.isNotEmpty())
                    latch.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    endNs.set(System.nanoTime())
                    latch.countDown()
                }
            }
        )

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            cancel.cancel()
            return null
        }
        return if (gotAnswer.get()) (endNs.get() - startNs) / 1e6 else null
    }
}

/* ======================================================================
 * 2. LATENCY + PACKET LOSS  -  one experiment, two readouts.
 *    N single-datagram probes: each returns an RTT or times out (= lost).
 *    Uses a raw UDP DNS query to a public resolver IP (no root, no ICMP).
 * ====================================================================== */
object UdpProbe {
    fun run(
        network: Network,
        server: String = "1.1.1.1",
        count: Int = 20,
        timeoutMs: Int = 1000,
        gapMs: Long = 100
    ): LatencyMetrics {
        val addr = InetAddress.getByName(server) // literal IP -> no DNS lookup happens
        val rtts = ArrayList<Double>()
        var lost = 0

        val socket = DatagramSocket()
        try {
            network.bindSocket(socket) // pin this socket to the SIM's network
            val buf = ByteArray(512)

            for (i in 0 until count) {
                val id = (Math.random() * 65536).toInt()
                val q = buildDnsQuery(id, "example.com")
                val out = DatagramPacket(q, q.size, addr, 53)
                val resp = DatagramPacket(buf, buf.size)

                val start = System.nanoTime()
                val deadline = start + timeoutMs * 1_000_000L
                var matched = false
                try {
                    socket.send(out)
                    while (!matched) {
                        val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                        if (remainingMs <= 0) throw SocketTimeoutException()
                        socket.soTimeout = remainingMs
                        resp.length = buf.size
                        socket.receive(resp)
                        val end = System.nanoTime()
                        val rid = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                        val isResponse = (buf[2].toInt() and 0x80) != 0
                        if (resp.length >= 12 && rid == id && isResponse) {
                            rtts.add((end - start) / 1e6)
                            matched = true
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    lost++
                } catch (e: IOException) {
                    lost++
                }
                if (i < count - 1) Thread.sleep(gapMs)
            }
        } finally {
            socket.close()
        }
        return LatencyMetrics(count, lost, Stats.summarize(rtts), Stats.jitter(rtts))
    }

    /** Minimal DNS query packet: header + one A/IN question, recursion desired. */
    private fun buildDnsQuery(id: Int, name: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(id shr 8); o.write(id and 0xFF)
        o.write(0x01); o.write(0x00)            // flags: RD
        o.write(0); o.write(1)                  // QDCOUNT = 1
        repeat(6) { o.write(0) }                // ANCOUNT, NSCOUNT, ARCOUNT = 0
        for (label in name.split('.')) {
            o.write(label.length)
            o.write(label.toByteArray(Charsets.US_ASCII))
        }
        o.write(0)                              // end of name
        o.write(0); o.write(1)                  // QTYPE = A
        o.write(0); o.write(1)                  // QCLASS = IN
        return o.toByteArray()
    }
}

/* ======================================================================
 * 3. THROUGHPUT  -  bounded download + upload over HTTPS.
 *    Byte- and time-capped because mobile data is money.
 * ====================================================================== */
object ThroughputProbe {
    // Configurable endpoints. Swap for your own server later (your FastAPI box).
    var DOWN_URL = "https://speed.cloudflare.com/__down?bytes="
    var UP_URL = "https://speed.cloudflare.com/__up"

    fun run(
        network: Network,
        downBytes: Long = 3_000_000L,
        upBytes: Int = 1_000_000,
        maxMs: Long = 8000
    ): ThroughputMetrics {
        // Sequential on purpose: never overlap with each other or with latency probes.
        val (downMbps, ttfb, downGot) = download(network, downBytes, maxMs)
        val (upMbps, upGot) = upload(network, upBytes, maxMs)
        return ThroughputMetrics(downMbps, ttfb, downGot, upMbps, upGot)
    }

    private fun download(network: Network, maxBytes: Long, maxMs: Long): Triple<Double?, Double?, Long> {
        var conn: HttpURLConnection? = null
        try {
            conn = network.openConnection(URL(DOWN_URL + maxBytes)) as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")

            val t0 = System.nanoTime()
            val input = conn.inputStream
            if (conn.responseCode !in 200..299) return Triple(null, null, 0L)

            val buf = ByteArray(32 * 1024)
            val first = input.read(buf)
            val tFirst = System.nanoTime()
            if (first <= 0) return Triple(null, null, 0L)
            val ttfbMs = (tFirst - t0) / 1e6 // includes DNS + TCP + TLS + request

            // Throughput is measured AFTER the first byte, so setup cost is excluded.
            var bytesAfter = 0L
            var tLast = tFirst
            while ((tLast - tFirst) / 1_000_000L <= maxMs) {
                val n = input.read(buf)
                if (n < 0) break
                bytesAfter += n
                tLast = System.nanoTime()
            }
            val secs = (tLast - tFirst) / 1e9
            val mbps = if (bytesAfter > 0 && secs > 0.05) bytesAfter * 8 / secs / 1e6 else null
            return Triple(mbps, ttfbMs, bytesAfter + first)
        } catch (e: IOException) {
            return Triple(null, null, 0L)
        } finally {
            conn?.disconnect() // also aborts any unread remainder when we hit the time cap
        }
    }

    private fun upload(network: Network, totalBytes: Int, maxMs: Long): Pair<Double?, Long> {
        var conn: HttpURLConnection? = null
        try {
            conn = network.openConnection(URL(UP_URL)) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.connectTimeout = 5000
            conn.readTimeout = maxMs.toInt()
            conn.setFixedLengthStreamingMode(totalBytes) // stream, don't buffer in RAM
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.connect() // handshake happens here, BEFORE the clock starts

            val chunk = ByteArray(16 * 1024)
            val t0 = System.nanoTime()
            conn.outputStream.use { out ->
                var sent = 0
                while (sent < totalBytes) {
                    val n = minOf(chunk.size, totalBytes - sent)
                    out.write(chunk, 0, n)
                    sent += n
                    if ((System.nanoTime() - t0) / 1_000_000L > maxMs) return Pair(null, sent.toLong())
                }
                out.flush()
            }
            // Wait for the server's reply: proves the bytes were actually delivered,
            // not just parked in a kernel send buffer.
            val code = conn.responseCode
            val secs = (System.nanoTime() - t0) / 1e9
            if (code !in 200..299 || secs <= 0.0) return Pair(null, totalBytes.toLong())
            return Pair(totalBytes.toLong() * 8 / secs / 1e6, totalBytes.toLong())
        } catch (e: IOException) {
            return Pair(null, 0L)
        } finally {
            conn?.disconnect()
        }
    }
}
