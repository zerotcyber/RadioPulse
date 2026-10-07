package com.example.cellprobe.netperf

import org.json.JSONObject

// ---------- Input ----------

/** Build these from your existing SubscriptionInfo: subId = info.subscriptionId, carrier = info.carrierName */
data class SimInfo(val subId: Int, val carrier: String?)

// ---------- Result types (null = "could not measure", never 0) ----------

data class Summary(
    val count: Int,
    val minMs: Double?,
    val medianMs: Double?,
    val p95Ms: Double?,
    val maxMs: Double?
)

data class DnsMetrics(
    val attempts: Int,
    val failures: Int,
    val rtt: Summary
)

data class LatencyMetrics(
    val sent: Int,
    val lost: Int,
    val rtt: Summary,
    val jitterMs: Double?
) {
    val lossPct: Double? get() = if (sent == 0) null else lost * 100.0 / sent
}

data class ThroughputMetrics(
    val downMbps: Double?,
    val downTtfbMs: Double?,
    val downBytes: Long,
    val upMbps: Double?,
    val upBytes: Long
)

data class SimSnapshot(
    val subId: Int,
    val carrier: String?,
    val tsMillis: Long,
    val networkAcquired: Boolean,
    val dns: DnsMetrics?,
    val latency: LatencyMetrics?,
    val throughput: ThroughputMetrics?,
    val error: String?
)

// ---------- Stats ----------

object Stats {
    /** Nearest-rank percentile on an already-sorted list. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun summarize(samples: List<Double>): Summary {
        if (samples.isEmpty()) return Summary(0, null, null, null, null)
        val s = samples.sorted()
        return Summary(s.size, s.first(), percentile(s, 50.0), percentile(s, 95.0), s.last())
    }

    /** Mean absolute difference between consecutive samples (send order). */
    fun jitter(inOrder: List<Double>): Double? {
        if (inOrder.size < 2) return null
        var sum = 0.0
        for (i in 1 until inOrder.size) sum += Math.abs(inOrder[i] - inOrder[i - 1])
        return sum / (inOrder.size - 1)
    }
}

// ---------- JSON (org.json is built into Android: zero dependencies) ----------
// NOTE: JSONObject.put(key, null) silently DELETES the key, so nulls must be JSONObject.NULL.

private fun JSONObject.putN(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

fun Summary.toJson(): JSONObject = JSONObject()
    .putN("count", count)
    .putN("min_ms", minMs)
    .putN("median_ms", medianMs)
    .putN("p95_ms", p95Ms)
    .putN("max_ms", maxMs)

fun DnsMetrics.toJson(): JSONObject = JSONObject()
    .putN("attempts", attempts)
    .putN("failures", failures)
    .putN("rtt", rtt.toJson())

fun LatencyMetrics.toJson(): JSONObject = JSONObject()
    .putN("sent", sent)
    .putN("lost", lost)
    .putN("loss_pct", lossPct)
    .putN("jitter_ms", jitterMs)
    .putN("rtt", rtt.toJson())

fun ThroughputMetrics.toJson(): JSONObject = JSONObject()
    .putN("down_mbps", downMbps)
    .putN("down_ttfb_ms", downTtfbMs)
    .putN("down_bytes", downBytes)
    .putN("up_mbps", upMbps)
    .putN("up_bytes", upBytes)

fun SimSnapshot.toJson(): JSONObject = JSONObject()
    .putN("sub_id", subId)
    .putN("carrier", carrier)
    .putN("ts_ms", tsMillis)
    .putN("network_acquired", networkAcquired)
    .putN("dns", dns?.toJson())
    .putN("latency", latency?.toJson())
    .putN("throughput", throughput?.toJson())
    .putN("error", error)
