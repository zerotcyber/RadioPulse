**Date:** 2026-11-15
**Scope:** `backend/app/schemas.py`: `Summary`, `DnsMetrics`, `LatencyMetrics`, `ThroughputMetrics`

## Context

CellProbe (Android) produces NetPerf snapshots and serialises them in `Models.kt` (`toJson()`). The backend must validate every record before it reaches PostgreSQL. The four leaf types in this record sit inside every NetPerf record, so their rules apply to every upload.

Not covered here (separate records): the `radio` and `netperf` blocks, the batch envelope, `schema_version`, `measurement_id`, per-item results, and the database schema.

## Decisions

### D1. Mirror the producer's shape
- **Decision:** Field names and nesting match the app's `toJson()` output (snake_case, for example `min_ms`, `down_ttfb_ms`).
- **Why:** The app's types already work and were tested on a real device. The server adapts to the app, not the reverse.
- **Trade-off:** The server schema is coupled to the app's naming. Any change needs a `schema_version` bump.

### D2. `null` means "could not measure", never 0
- **Decision:** Nullable numeric fields accept `null`. Zero is used only for a real zero. Nulls are stored as SQL `NULL`.
- **Why:** SQL aggregates such as `AVG` skip `NULL`. A fake `0` would drag averages down and hide failures. The app already follows this rule.
- **Trade-off:** Queries must handle `NULL` deliberately (filter, or `COALESCE` with intent).

### D3. Nullable fields are required keys
- **Decision:** Nullable fields have no default. The key must be present. The value may be `null`.
- **Why:** `Models.kt` notes that `JSONObject.put(key, null)` silently deletes the key. If a bug drops a key, a default would turn it into a plausible-looking `null` and hide the bug. A required key makes the server reject the record, so the bug becomes visible.
- **Evidence:** The first draft used `default=None`. `Summary.model_validate({"count": 0})` was accepted. After removing the defaults, the same input fails with 4 errors.
- **Trade-off:** Any other client must send every key.

### D4. Each block validates itself
- **Decision:** Cross-field rules live in model validators on the block. A block is valid only if its own invariants hold, not merely because it exists. An empty `{}` is not a measurement.
- **Why:** A half-filled block must not count as data.
- **Trade-off:** `mode="after"` validators run only when every field-level check has passed. One request can therefore report field errors first and consistency errors on a later attempt.

### D5. Non-negative constraints
- **Decision:** `ge=0` on counts, durations, speeds and byte counts.
- **Why:** Negative values are corrupt data and cheap to detect.
- **Evidence:** Tested that `Field(ge=0)` still applies to `float | None` (`-5` rejected, `12.5` accepted).

### D6. `Summary` invariants
- **Decision:** `count == 0` means all four statistics are `null`. `count > 0` means all four are present and `min <= median <= p95 <= max`.
- **Why:** The ordering holds by construction: statistics come from a sorted sample using nearest-rank percentiles (`Stats.summarize`). A violation means corruption.
- **Note:** Nearest-rank p95 equals the maximum for any sample under 20 values, so p95 is not meaningful for DNS (10 samples). See limitations.

### D7. DNS invariants
- **Decision:** `failures <= attempts` and `rtt.count == attempts - failures`.
- **Why:** By construction in the DNS probe: every attempt increments `attempts`, then either increments `failures` or adds one RTT.
- **Trade-off:** The rule couples the server to the probe's counting method. If counting changes, bump `schema_version`.

### D8. Latency invariants
- **Decision:** `lost <= sent` and `rtt.count == sent - lost`.
- **Why:** By construction in the latency probe: each probe either records one RTT or increments `lost`. Timeouts and `IOException` both count as lost. The app passes the number of probes as `sent`.

### D9. Derived values are not trusted from the client
- **Decision:** `loss_pct` is not a model field. The server derives loss from `sent` and `lost`. Unknown keys are ignored (Pydantic default).
- **Why:** One source of truth; a client cannot send a loss percentage that contradicts its own counts.
- **Trade-off:** A mistyped key name is ignored instead of rejected. `schema_version` handles deliberate changes. Revisit `extra="forbid"` once clients are stable.

### D10. Throughput invariants
- **Decision:** `down_mbps` non-null requires `down_bytes > 0` and `down_ttfb_ms` non-null. `up_mbps` non-null requires `up_bytes > 0`.
- **Why:** By construction: `download()` computes speed only when bytes after the first read are above 0 and returns it together with TTFB. `upload()` returns a speed only on a 2xx reply, with `totalBytes` as the byte count.
- **Note:** `up_mbps` null with `up_bytes > 0` is valid (rejected reply, or the time cap was hit).

### D11. Byte semantics
- **Decision:** `down_bytes` is bytes received. `up_bytes` is bytes written to the connection, not bytes the server accepted.
- **Why:** That is what the probes measure. A rejected upload reports the full size with a null speed.
- **Consequence:** Any "data used" analysis must account for this.

### D12. Enforce only what is true by construction
- **Decision:** Every rule above was checked against `Probes.kt` before being kept. Rules a legitimate record could break (for example, speed roughly equal to bytes divided by duration) are not enforced.
- **Why:** A rejection is final for the offline queue: a rejected record is marked failed and not retried. A wrong rule therefore loses valid data.
- **Process:** A new rule needs evidence from the producer code. If it changes meaning, bump `schema_version`.

## Known limitations

- A download that times out mid-transfer returns `(null, null, 0)`. Partial bytes are discarded, so `down_bytes = 0` is ambiguous: nothing arrived, or it failed partway.
- `IOException` is swallowed in the probes, so a null result carries no reason.
- Jitter is the mean difference between consecutive received replies. Lost packets are skipped.
- p95 equals the maximum for samples under 20 values. DNS uses 10 samples, so its p95 is effectively the maximum.
- Throughput uses small payloads (3 MB down, 1 MB up). TCP ramp-up likely limits the result, so treat figures as indicative. Upload is probably the most affected.

