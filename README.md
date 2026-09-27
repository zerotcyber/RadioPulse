CellProbe — Phase 1 feasibility prototype

Status: complete. This answered one question: does an Android device using Nigerian SIMs expose enough cellular telemetry to justify building a telemetry backend around it? — before any backend, database, or infrastructure work was started.

No FastAPI, no Postgres, no Redis, no Docker, no AWS in this repo. That comes next in a separate project, informed by what this proved.

Result: yes

Tested on a Samsung Galaxy S20 (SM-G981B, Android 13 / API 33), dual-SIM (MTN Nigeria + Airtel Nigeria), in Enugu.

Check	                                      Outcome
RSRP / RSRQ / RSSNR (SINR) available	      Yes, on both SIMs, every poll
Values respond to live querying (not cached)  Yes see below
Values track physical movement	              Yes confirmed while walking
Real handover captured mid-test	              Yes Airtel switched serving cells
Dual-SIM independently probed	              Yes
RSSI	                                      Unavailable on this modem for LTE
Cell/band info	                              Unavailable on this modem
5G NSA override	                              Not observed (no NR session during testing)
Evidence

Ten consecutive polls (10s apart) while walking, both SIMs:

MTN: RSRP moved from -59 dBm to -83 dBm and back across the walk. A 24 dBm swing directly attributable to distance/obstruction changes, not noise.
Airtel: RSRP ranged -94 to -102 dBm (consistently weaker than MTN at this location), and the serving cell changed mid-test — cell ID 33432875 → 33432789, PCI 13 → 220, EARFCN 225 → 3256. That is a live handover, which a cached or static API response cannot produce.

This rules out the two failure modes that would have killed my project: telemetry that's frozen/cached, and telemetry that doesn't correlate with the real world.

What this means for the backend design
RSRP + RSRQ + RSSNR is the reliable core. Design the analyzer around these three being present, not optional.
RSSI and band info should be modeled as null-able from day one, not bolted on later. This device never populates them for LTE; other devices might. The schema has to expect gaps.
Dual-SIM matters for this market. Two SIMs, same phone, same moment, meaningfully different signal quality (MTN materially stronger than Airtel at this location). A telemetry record needs a SIM/subscription identifier, not just a device identifier — "this phone's network condition" is ambiguous on a dual-SIM device.
Single readings are noisy. A 24 dB swing on a healthy connection means the analyzer must smooth or window readings (e.g. rolling average over N samples) rather than react to any single poll — otherwise normal movement looks like a signal fault.
5G NSA detection code exists but is unverified. The TelephonyDisplayInfo override path (API 31+) never fired during testing because no NR session was available. It should be treated as untested until confirmed on a live 5G connection.
Limitations

WARNING: Software cannot increase the physical radio capacity of a cellular tower or overcome severe RF attenuation. This project identifies network conditions and optimizes factors under application/server control — it does not, and cannot, "boost" a phone's internet connection.

Tower-side congestion cannot be proven from a single client device; the planned backend can only report conditions consistent with congestion (good radio quality alongside poor throughput), never confirm it, since tower utilization data isn't available to a handset.

Build & test protocol:

See TESTING.md for full build steps and the walk-around test procedure this project's results are based on.

APIs used:
API/permission	                              Purpose
TelephonyManager/SubscriptionManager	      Entry points; createForSubscriptionId() for per-SIM reads.
getAllCellInfo()/requestCellInfoUpdate()	  Serving + neighbour cells. Needs ACCESS_FINE_LOCATION.
CellInfoLte/CellSignalStrengthLte	          rsrp, rsrq, rssnr, level, rssi (API 29+).
CellIdentityLte	                              ci, pci, tac, earfcn, mccString/mncString, bands.
getDataNetworkType()	                      Current RAT. Needs READ_PHONE_STATE.
TelephonyCallback.DisplayInfoListener	      API 31+. 5G NSA override detection (unverified — see above).
getSignalStrength()	                          API 28+. Permission-free fallback tier.
