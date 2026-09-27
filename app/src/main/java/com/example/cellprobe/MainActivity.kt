package com.example.cellprobe

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Feasibility prototype. Single purpose: find out which cellular telemetry fields
 * this specific handset + SIM(s) + Android version actually expose, and whether
 * they keep changing when polled repeatedly.
 *
 * Dual-SIM aware: probes every active subscription independently, since each SIM
 * can be registered on a different tower or even a different carrier.
 *
 * Deliberately has no backend, no database, no upload path, no architecture.
 */
class MainActivity : ComponentActivity() {

    /** Why a metric is or is not present. This distinction is the whole point. */
    enum class Status {
        AVAILABLE,          // modem returned a real value
        UNAVAILABLE,        // API exists, permission granted, modem gave nothing
        PERMISSION_DENIED,  // we were not allowed to ask
        NOT_SUPPORTED       // API does not exist on this Android version
    }

    /** A probed metric: its availability status and, if available, its rendered value. */
    data class Probe(val status: Status, val value: String? = null)

    companion object {
        const val TAG = "CellProbe"

        /**
         * CellInfo.UNAVAILABLE is API 29+, redeclared here to keep minSdk 26.
         * Android returns this sentinel (Int.MAX_VALUE) when the modem or API has
         * no value. Treating it as a real measurement is the most common bug in
         * code that reads these APIs - a "signal strength" of 2147483647.
         */
        const val UNAVAILABLE_INT = Int.MAX_VALUE
        const val UNAVAILABLE_LONG = Long.MAX_VALUE

        const val POLL_INTERVAL_MS = 10_000L
    }

    private lateinit var defaultTm: TelephonyManager
    private lateinit var subscriptionManager: SubscriptionManager
    private lateinit var output: TextView
    private lateinit var pollButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var polling = false
    private var pollCount = 0

    /** 5G NSA override per subscription id. API 31+ only; empty until callbacks fire. */
    private val displayInfoOverrides = HashMap<Int, String>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            registerDisplayInfoCallbacks()
            probeOnce()
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        defaultTm = getSystemService(TelephonyManager::class.java)
        subscriptionManager = getSystemService(SubscriptionManager::class.java)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val probeButton = Button(this).apply {
            text = "Probe once"
            setOnClickListener { requestPermissionsThenProbe() }
        }

        pollButton = Button(this).apply {
            text = "Start polling (10s)"
            setOnClickListener { togglePolling() }
        }

        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = "Press \"Probe once\".\n\n" +
                "Turn system location services ON before testing. Several OEMs " +
                "return an empty cell list when location is off at the system " +
                "level, even with the runtime permission granted.\n\n" +
                "Dual-SIM: both active SIMs are probed independently and shown " +
                "as separate blocks below."
        }

        val scroll = ScrollView(this).apply {
            addView(output)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        root.addView(probeButton)
        root.addView(pollButton)
        root.addView(scroll)
        setContentView(root)

        registerDisplayInfoCallbacks()
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }

    // -------------------------------------------------------------- permissions

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private val hasLocation get() = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private val hasPhoneState get() = hasPermission(Manifest.permission.READ_PHONE_STATE)

    private fun requestPermissionsThenProbe() {
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        ).filterNot { hasPermission(it) }

        if (missing.isEmpty()) probeOnce() else permissionLauncher.launch(missing.toTypedArray())
    }

    // ------------------------------------------------------------ subscriptions

    /**
     * All currently active SIM subscriptions (both slots on a dual-SIM phone,
     * when both have a SIM inserted and enabled). Requires READ_PHONE_STATE on
     * API 26-28; API 29+ also accepts READ_PHONE_NUMBERS or carrier privileges,
     * but READ_PHONE_STATE - which this app already requests - is sufficient.
     */
    private fun activeSubscriptions(): List<SubscriptionInfo> {
        if (!hasPhoneState) return emptyList()
        return try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSubscriptionInfoList denied: ${e.message}")
            emptyList()
        }
    }

    /** Per-SIM TelephonyManager. Falls back to the default instance if this fails. */
    private fun telephonyManagerFor(subId: Int): TelephonyManager =
        try {
            defaultTm.createForSubscriptionId(subId)
        } catch (e: Exception) {
            Log.w(TAG, "createForSubscriptionId($subId) failed: ${e.message}")
            defaultTm
        }

    private fun simLabel(sub: SubscriptionInfo): String {
        val slot = sub.simSlotIndex + 1 // human-friendly, 1-based
        val carrier = sub.carrierName?.toString()?.ifBlank { null }
            ?: sub.displayName?.toString()?.ifBlank { null }
            ?: "unknown carrier"
        return "SIM $slot ($carrier, subId=${sub.subscriptionId})"
    }

    // -------------------------------------------- 5G NSA display info (API 31+)

    /**
     * On a Galaxy S20, a 5G NSA connection still reports LTE from getDataNetworkType().
     * The NR leg only appears as an *override* in TelephonyDisplayInfo. Registered
     * per subscription id, since each SIM can be on a different RAT.
     */
    private fun registerDisplayInfoCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!hasPhoneState) return
        activeSubscriptions().forEach { sub ->
            val subId = sub.subscriptionId
            try {
                telephonyManagerFor(subId).registerTelephonyCallback(
                    executor,
                    object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                            displayInfoOverrides[subId] = when (telephonyDisplayInfo.overrideNetworkType) {
                                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE -> "NONE"
                                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE_CA"
                                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE_ADVANCED_PRO"
                                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "NR_NSA"
                                else -> "OTHER(${telephonyDisplayInfo.overrideNetworkType})"
                            }
                        }
                    }
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "registerTelephonyCallback denied for subId $subId: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ polling

    private fun togglePolling() {
        polling = !polling
        pollButton.text = if (polling) "Stop polling" else "Start polling (10s)"
        if (polling) {
            pollCount = 0
            scheduleNextPoll(immediate = true)
        } else {
            handler.removeCallbacksAndMessages(null)
        }
    }

    private fun scheduleNextPoll(immediate: Boolean = false) {
        if (!polling) return
        handler.postDelayed({
            if (!polling) return@postDelayed
            pollCount++
            probeOnce(isPoll = true)
            scheduleNextPoll()
        }, if (immediate) 0L else POLL_INTERVAL_MS)
    }

    // -------------------------------------------------------------------- probe

    /**
     * Probes every active subscription independently and renders them together
     * once all have responded (or failed). Each SIM gets its own report block.
     */
    private fun probeOnce(isPoll: Boolean = false) {
        val subs = activeSubscriptions()

        if (subs.isEmpty()) {
            // No subscription info available (permission issue, or single-SIM
            // device where the API just isn't giving us subscription details).
            // Fall back to the default TelephonyManager, unlabeled.
            probeOneSim(defaultTm, label = "default SIM (subscription list unavailable)") { report ->
                render(listOf(report), isPoll)
            }
            return
        }

        val results = arrayOfNulls<Report>(subs.size)
        val remaining = AtomicInteger(subs.size)

        subs.forEachIndexed { index, sub ->
            val tm = telephonyManagerFor(sub.subscriptionId)
            probeOneSim(tm, label = simLabel(sub)) { report ->
                results[index] = report
                if (remaining.decrementAndGet() == 0) {
                    runOnUiThread { render(results.filterNotNull(), isPoll) }
                }
            }
        }
    }

    /** Probes a single (possibly SIM-specific) TelephonyManager and returns a Report. */
    private fun probeOneSim(tm: TelephonyManager, label: String, onDone: (Report) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocation) {
            try {
                tm.requestCellInfoUpdate(
                    executor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            onDone(buildReport(tm, cellInfo, "requestCellInfoUpdate", label))
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            Log.w(TAG, "requestCellInfoUpdate error $errorCode for $label", detail)
                            onDone(
                                buildReport(
                                    tm, readAllCellInfo(tm), "getAllCellInfo (update err $errorCode)", label
                                )
                            )
                        }
                    }
                )
            } catch (e: SecurityException) {
                onDone(buildReport(tm, null, "denied", label))
            }
        } else {
            onDone(buildReport(tm, readAllCellInfo(tm), "getAllCellInfo", label))
        }
    }

    private fun readAllCellInfo(tm: TelephonyManager): List<CellInfo>? =
        if (!hasLocation) null
        else try {
            @Suppress("DEPRECATION")
            tm.allCellInfo
        } catch (e: SecurityException) {
            Log.w(TAG, "getAllCellInfo denied: ${e.message}")
            null
        }

    // ------------------------------------------------------------- report model

    private class Report {
        val lines = StringBuilder()
        val csv = LinkedHashMap<String, String>()

        fun line(s: String) { lines.append(s).append('\n') }
        fun header(s: String) { lines.append('\n').append(s).append('\n') }

        fun metric(label: String, p: Probe, csvKey: String? = null) {
            val shown = when (p.status) {
                Status.AVAILABLE -> p.value ?: "?"
                Status.UNAVAILABLE -> "UNAVAILABLE (modem returned no value)"
                Status.PERMISSION_DENIED -> "PERMISSION_DENIED"
                Status.NOT_SUPPORTED -> "NOT_SUPPORTED on this Android version"
            }
            lines.append("  ").append(label.padEnd(22)).append(shown).append('\n')
            if (csvKey != null) {
                csv[csvKey] = if (p.status == Status.AVAILABLE) (p.value ?: "") else ""
            }
        }
    }

    private fun intProbe(v: Int, unit: String = ""): Probe =
        if (v == UNAVAILABLE_INT) Probe(Status.UNAVAILABLE)
        else Probe(Status.AVAILABLE, "$v$unit")

    private fun longProbe(v: Long): Probe =
        if (v == UNAVAILABLE_LONG || v == UNAVAILABLE_INT.toLong()) Probe(Status.UNAVAILABLE)
        else Probe(Status.AVAILABLE, v.toString())

    private fun strProbe(s: String?): Probe =
        if (s.isNullOrEmpty()) Probe(Status.UNAVAILABLE) else Probe(Status.AVAILABLE, s)

    private fun plain(s: String): Probe = Probe(Status.AVAILABLE, s)

    // ------------------------------------------------------------ report builder

    private fun buildReport(
        tm: TelephonyManager,
        cells: List<CellInfo>?,
        source: String,
        simLabel: String
    ): Report {
        val r = Report()
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        r.line("=".repeat(56))
        r.line("SIM              $simLabel")
        r.line("timestamp        $ts")
        r.line("poll #           $pollCount")
        r.line("source           $source")
        r.line("android SDK      ${Build.VERSION.SDK_INT}")
        r.line("device           ${Build.MANUFACTURER} ${Build.MODEL}")
        r.line("perm FINE_LOC    ${if (hasLocation) "granted" else "DENIED"}")
        r.line("perm PHONE_STATE ${if (hasPhoneState) "granted" else "DENIED"}")
        r.csv["sim"] = simLabel
        r.csv["timestamp"] = ts
        r.csv["poll"] = pollCount.toString()
        r.csv["source"] = source

        reportCarrier(r, tm)
        reportServingCell(r, cells)
        reportSignalStrengthFallback(r, tm)
        reportNeighbours(r, cells)

        return r
    }

    private fun reportCarrier(r: Report, tm: TelephonyManager) {
        r.header("CARRIER / NETWORK")
        r.metric("operator name", strProbe(tm.networkOperatorName), "operator")
        r.metric("sim operator", strProbe(tm.simOperatorName))
        r.metric("network operator", strProbe(tm.networkOperator), "mccmnc")

        // getDataNetworkType() requires READ_PHONE_STATE and throws without it.
        // Note: this reflects the device's active DATA connection, which may not
        // be this SIM if the phone is set to use the other SIM for data.
        val netType = try {
            if (!hasPhoneState) Probe(Status.PERMISSION_DENIED)
            else plain(networkTypeName(tm.dataNetworkType))
        } catch (e: SecurityException) {
            Probe(Status.PERMISSION_DENIED)
        }
        r.metric("data network type", netType, "network_type")

        val override = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> Probe(Status.NOT_SUPPORTED)
            !hasPhoneState -> Probe(Status.PERMISSION_DENIED)
            else -> Probe(Status.UNAVAILABLE) // resolved per-subId in registerDisplayInfoCallbacks
        }
        r.metric("5G NSA override", override, "nr_override")
    }

    private fun reportServingCell(r: Report, cells: List<CellInfo>?) {
        r.header("SERVING CELL")

        if (cells == null) {
            r.line("  cell list unavailable (permission denied or SecurityException)")
            return
        }
        if (cells.isEmpty()) {
            r.line("  cell list EMPTY.")
            r.line("  Usual causes: system location services off, battery saver,")
            r.line("  or OEM throttling of cell info updates.")
            return
        }

        val serving = cells.firstOrNull { it.isRegistered } ?: cells.first()
        r.line("  registered:           ${serving.isRegistered}")
        r.csv["cell_type"] = serving.javaClass.simpleName

        when {
            serving is CellInfoLte -> reportLte(r, serving)
            serving is CellInfoWcdma -> reportUnspecific(r, serving, "WCDMA / 3G")
            serving is CellInfoGsm -> reportUnspecific(r, serving, "GSM / 2G")
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && serving is CellInfoNr ->
                reportNr(r, serving)
            else -> r.line("  unhandled cell type: ${serving.javaClass.simpleName}")
        }
    }

    private fun reportLte(r: Report, cell: CellInfoLte) {
        r.line("  radio access tech:    LTE")
        val s: CellSignalStrengthLte = cell.cellSignalStrength
        val id: CellIdentityLte = cell.cellIdentity

        r.metric("RSRP", intProbe(s.rsrp, " dBm"), "rsrp")
        r.metric("RSRQ", intProbe(s.rsrq, " dB"), "rsrq")
        // rssnr is LTE's stand-in for SINR. Plenty of OEMs never populate it.
        r.metric("RSSNR (SINR)", intProbe(s.rssnr, " dB"), "sinr")
        r.metric("level (0-4)", plain(s.level.toString()), "level")
        r.metric("asu level", plain(s.asuLevel.toString()))
        r.metric("timing advance", intProbe(s.timingAdvance))

        r.metric(
            "RSSI",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intProbe(s.rssi, " dBm")
            else Probe(Status.NOT_SUPPORTED),
            "rssi"
        )

        r.metric("cell id (CI)", intProbe(id.ci), "ci")
        r.metric("PCI", intProbe(id.pci), "pci")
        r.metric("TAC", intProbe(id.tac), "tac")
        r.metric("EARFCN", intProbe(id.earfcn), "earfcn")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            r.metric("MCC", strProbe(id.mccString), "mcc")
            r.metric("MNC", strProbe(id.mncString), "mnc")
        } else {
            r.metric("MCC", Probe(Status.NOT_SUPPORTED), "mcc")
            r.metric("MNC", Probe(Status.NOT_SUPPORTED), "mnc")
        }

        r.metric(
            "bands",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) strProbe(id.bands.joinToString(","))
            else Probe(Status.NOT_SUPPORTED)
        )
    }

    private fun reportNr(r: Report, cell: CellInfoNr) {
        r.line("  radio access tech:    NR (standalone 5G cell entry)")
        val s = cell.cellSignalStrength as CellSignalStrengthNr
        val id = cell.cellIdentity as CellIdentityNr

        r.metric("SS-RSRP", intProbe(s.ssRsrp, " dBm"), "rsrp")
        r.metric("SS-RSRQ", intProbe(s.ssRsrq, " dB"), "rsrq")
        r.metric("SS-SINR", intProbe(s.ssSinr, " dB"), "sinr")
        r.metric("CSI-RSRP", intProbe(s.csiRsrp, " dBm"))
        r.metric("CSI-RSRQ", intProbe(s.csiRsrq, " dB"))
        r.metric("CSI-SINR", intProbe(s.csiSinr, " dB"))
        r.metric("level (0-4)", plain(s.level.toString()), "level")

        r.metric("NCI", longProbe(id.nci), "ci")
        r.metric("PCI", intProbe(id.pci), "pci")
        r.metric("TAC", intProbe(id.tac), "tac")
        r.metric("NRARFCN", intProbe(id.nrarfcn), "earfcn")
        r.metric("MCC", strProbe(id.mccString), "mcc")
        r.metric("MNC", strProbe(id.mncString), "mnc")

        r.metric(
            "bands",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) strProbe(id.bands.joinToString(","))
            else Probe(Status.NOT_SUPPORTED)
        )
    }

    /** 2G/3G fall back to a bare dBm - useful to see, not useful to model on. */
    private fun reportUnspecific(r: Report, cell: CellInfo, label: String) {
        r.line("  radio access tech:    $label")
        val s = cell.cellSignalStrength
        r.metric("dBm", intProbe(s.dbm, " dBm"), "rsrp")
        r.metric("level (0-4)", plain(s.level.toString()), "level")
        r.metric("asu level", plain(s.asuLevel.toString()))
    }

    /**
     * Fallback path. getSignalStrength() is API 28+ and needs NO location permission.
     * If the cell list comes back empty or redacted, this may still give a usable
     * signal level - exactly the degradation the backend schema should expect.
     */
    private fun reportSignalStrengthFallback(r: Report, tm: TelephonyManager) {
        r.header("FALLBACK: getSignalStrength() (no location permission needed)")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            r.line("  NOT_SUPPORTED below API 28")
            return
        }
        val ss = tm.signalStrength
        if (ss == null) {
            r.line("  returned null")
            r.csv["fallback_level"] = ""
            return
        }
        r.metric("overall level (0-4)", plain(ss.level.toString()), "fallback_level")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val strengths: List<CellSignalStrength> = ss.cellSignalStrengths
            if (strengths.isEmpty()) r.line("  cellSignalStrengths list is empty")
            strengths.forEach {
                r.line("  ${it.javaClass.simpleName}: dbm=${it.dbm} level=${it.level} asu=${it.asuLevel}")
            }
        } else {
            r.line("  per-RAT breakdown NOT_SUPPORTED below API 29")
        }
    }

    private fun reportNeighbours(r: Report, cells: List<CellInfo>?) {
        r.header("NEIGHBOUR CELLS")
        if (cells.isNullOrEmpty()) {
            r.line("  none reported")
            r.csv["neighbours"] = "0"
            return
        }
        val neighbours = cells.filterNot { it.isRegistered }
        r.csv["neighbours"] = neighbours.size.toString()
        r.line("  count: ${neighbours.size}")
        neighbours.take(6).forEach {
            r.line("  ${it.javaClass.simpleName} dbm=${it.cellSignalStrength.dbm} level=${it.cellSignalStrength.level}")
        }
    }

    private fun networkTypeName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR (5G SA)"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "OTHER($t)"
    }

    // ------------------------------------------------------------------ output

    private fun render(reports: List<Report>, isPoll: Boolean) {
        val text = reports.joinToString("\n") { it.lines.toString() }
        Log.i(TAG, text)
        output.text = if (isPoll) text + "\n" + output.text else text
        reports.forEach { appendCsv(it.csv) }
    }

    /**
     * Written to Android/data/com.example.cellprobe/files/cell_probe.csv on the
     * device. One row per SIM per probe. Pull it over USB after walking around.
     */
    private fun appendCsv(row: Map<String, String>) {
        try {
            val f = File(getExternalFilesDir(null), "cell_probe.csv")
            if (!f.exists()) f.appendText(row.keys.joinToString(",") + "\n")
            f.appendText(row.values.joinToString(",") { it.replace(",", ";") } + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "csv write failed: ${e.message}")
        }
    }
}

