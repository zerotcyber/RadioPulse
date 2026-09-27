# TESTING.md — build steps and test protocol

## Build

1. Open this folder as a project (Android Studio, or an on-device IDE such as
   Code on the Go).
2. Enable Developer Options + USB debugging on the test device, if using a
   desktop IDE.
3. Run on a **physical device**. An emulator has no modem and cannot produce
   any of the telemetry this app reads.

`minSdk 26`, `compileSdk 34`, Kotlin, two dependencies (`androidx.core:core-ktx`,
`androidx.activity:activity-ktx`). No XML layouts — the UI is built in code.

## Test protocol

Do not stop at one successful probe. That proves almost nothing on its own.

1. **Grant both permissions** (`ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`) when
   prompted. Also switch **system location services ON** — several OEMs return
   an empty cell list when location is off at the system level, even with the
   runtime permission granted.
2. **Probe once.** Check which fields say `AVAILABLE`, `UNAVAILABLE`,
   `PERMISSION_DENIED`, or `NOT_SUPPORTED`. Those four outcomes mean different
   things — see the table in `README.md`.
3. **Start polling** (10s interval) and walk a real route: room → window →
   outside → upstairs → downstairs, or whatever mirrors your test environment.
   Values should track the RF environment as you move.
4. **Repeat on each active SIM** if the device is dual-SIM — each SIM can be
   registered on a different tower, or even behave completely differently.
5. **Pull the CSV log** afterward:
   `Android/data/com.example.cellprobe/files/cell_probe.csv`
   (accessible via USB file transfer, or a file manager app with storage
   access on newer Android versions).

## Reading the results

| Result | Verdict |
|---|---|
| RSRP + RSRQ + RSSNR all populated and moving with location | Full radio telemetry confirmed — build the analyzer as designed. |
| RSRP + RSRQ populated, RSSNR `UNAVAILABLE` | Common on some OEMs. Lose the clean "good radio, bad throughput" congestion inference; fall back to level + performance metrics. |
| Only `level` (0–4) populated | Radio path is thin. Project still works, leaning almost entirely on latency/loss/throughput instead. |
| Cell list empty, fallback level still works | Location gating or OEM throttling — investigate before concluding the hardware can't do it. |
| Values identical across every poll | You are hitting a cache or rate limiter, not the live modem. Lengthen the poll interval and retest. |
| Serving cell ID changes mid-test | A real handover — strong positive evidence the data is live, not cached. |

## Known platform quirks (found during testing on a Samsung Galaxy S20)

- **`Int.MAX_VALUE` is not a measurement.** `CellInfo.UNAVAILABLE` is the
  sentinel value `2147483647`. The app classifies this as `UNAVAILABLE`
  rather than a real reading — carry that same null-handling into any backend
  built on top of this data.
- **`ACCESS_COARSE_LOCATION` is not enough.** Serving-cell identity is treated
  as location data by Android; coarse-only permission returns redacted
  identity fields.
- **5G NSA reports as LTE.** `getDataNetworkType()` returns `LTE` even on a 5G
  NSA connection; the NR leg only appears as an override in
  `TelephonyDisplayInfo` (API 31+). This path exists in the code but was
  **not exercised** during testing — no NR session was available.
- **`RSSI` and `bands` were `UNAVAILABLE`** on this specific modem for every
  LTE reading, on both SIMs, throughout testing. Other devices may differ —
  design for the gap, don't assume it will always be populated.
- **`getSignalStrength()` needs no location permission** (API 28+) and is the
  fallback tier if the cell-info path is blocked entirely.

## Deliberately not included

No latency, packet loss, or throughput measurement in this phase. Those don't
depend on the telephony APIs at all and can be measured independently once the
radio layer's reliability is known. Keeping them out kept this test scoped to
exactly one question.
