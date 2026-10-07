package com.example.cellprobe.netperf

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/* ======================================================================
 * SimNetwork: holds a long-lived request for "the cellular Network that
 * belongs to subscription X". `network` is null whenever the OS cannot
 * (or will not) give us one, and that is DATA, not an error: it tells
 * you whether this phone can reach the secondary SIM's data path.
 * ====================================================================== */
class SimNetwork(context: Context, val subId: Int) {

    private val cm = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    @Volatile
    var network: Network? = null
        private set

    private val request: NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .setNetworkSpecifier(
            TelephonyNetworkSpecifier.Builder().setSubscriptionId(subId).build()
        )
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            network = n
        }

        override fun onLost(n: Network) {
            if (network == n) network = null
        }

        override fun onUnavailable() {
            network = null
        }
    }

    fun start() {
        cm.requestNetwork(request, callback) // stays pending until the OS can satisfy it
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
        network = null
    }
}

/* ======================================================================
 * NetPerfRunner: the orchestrator.
 *  - ONE worker thread  -> SIMs and probes never overlap (no cross-contamination)
 *  - fixed-DELAY schedule -> a slow cycle can never pile up behind itself
 *  - probe order: DNS -> latency/loss -> throughput (heaviest last)
 *  - throughput runs only every Nth cycle (data cost tier)
 *  - callback fires on the worker thread: post to main thread before touching UI
 * ====================================================================== */
class NetPerfRunner(
    context: Context,
    private val sims: List<SimInfo>,
    private val onResult: (SimSnapshot) -> Unit
) {
    private val handles: Map<Int, SimNetwork> =
        sims.associate { it.subId to SimNetwork(context, it.subId) }

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var cycle = 0L

    /**
     * @param fastEverySec   delay between the end of one cycle and the start of the next
     * @param throughputEvery run throughput on every Nth cycle (cycle 0 included)
     */
    fun start(fastEverySec: Long = 60, throughputEvery: Int = 30) {
        handles.values.forEach { it.start() }
        executor.scheduleWithFixedDelay({
            try {
                val withThroughput = cycle % throughputEvery == 0L
                for (sim in sims) onResult(measure(sim, withThroughput))
            } catch (t: Throwable) {
                // An uncaught exception would silently cancel all future runs.
            }
            cycle++
        }, 5, fastEverySec, TimeUnit.SECONDS)
    }

    fun stop() {
        executor.shutdownNow()
        handles.values.forEach { it.stop() }
    }

    private fun measure(sim: SimInfo, withThroughput: Boolean): SimSnapshot {
        val now = System.currentTimeMillis()
        val net = handles[sim.subId]?.network
            ?: return SimSnapshot(
                sim.subId, sim.carrier, now, false, null, null, null,
                "no cellular Network available for this subId"
            )
        return try {
            val dns = DnsProbe.run(net)
            val latency = UdpProbe.run(net)
            val throughput = if (withThroughput) ThroughputProbe.run(net) else null
            SimSnapshot(sim.subId, sim.carrier, now, true, dns, latency, throughput, null)
        } catch (t: Throwable) {
            SimSnapshot(
                sim.subId, sim.carrier, now, true, null, null, null,
                t.javaClass.simpleName + ": " + t.message
            )
        }
    }
}
