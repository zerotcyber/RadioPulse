from typing import Self, Literal
from pydantic import BaseModel, Field, model_validator

class Summary(BaseModel):
    count: int = Field(ge=0)
    min_ms: float | None = Field(ge=0)
    median_ms: float | None = Field(ge=0)
    p95_ms: float | None = Field(ge=0)
    max_ms: float | None = Field(ge=0)

    @model_validator(mode="after")
    def check_validity(self) -> Self:
        stats = [self.min_ms, self.median_ms, self.p95_ms, self.max_ms]
        if self.count == 0:
           if any(v is not None for v in stats):
               raise ValueError("count is 0 but statistics are present")
        else:
           if any(v is None for v in stats):
               raise ValueError("count > 0 but a statistic is missing")
           if not(self.min_ms <= self.median_ms <= self.p95_ms <= self.max_ms):
               raise ValueError("statistics are out of order")
        return self

class DnsMetrics(BaseModel):
    attempts: int = Field(ge=0)
    failures: int = Field(ge=0)
    rtt: Summary

    @model_validator(mode="after")
    def failures_within_attempts(self) -> Self:
        if self.failures > self.attempts:
            raise ValueError("failures cannot exceed attempts")
        if self.rtt.count != (self.attempts - self.failures):
            raise ValueError("rtt.count must equal successful attempts (attempts - failures)")
        return self

class LatencyMetrics(BaseModel):
    sent: int = Field(ge=0)
    lost: int = Field(ge=0)
    jitter_ms: float | None = Field(ge=0)
    rtt: Summary

    @model_validator(mode="after")
    def lost_within_sent(self) -> Self:
        if self.lost > self.sent:
            raise ValueError("lost cannot exceed sent")
        if self.rtt.count != (self.sent - self.lost):
            raise ValueError("rtt.count must equal received packets (sent - lost)")
        return self

class ThroughputMetrics(BaseModel):
    down_mbps: float | None = Field(ge=0)
    down_ttfb_ms: float | None = Field(ge=0)
    down_bytes: int = Field(ge=0)
    up_mbps: float | None = Field(ge=0)
    up_bytes: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_throughput_consistency(self) -> Self:
        if self.down_mbps is not None:
            if self.down_bytes == 0:
                raise ValueError("down_bytes must be > 0 when down_mbps is present")
            if self.down_ttfb_ms is None:
                raise ValueError("down_ttfb_ms must be present when down_mbps is present")

            if self.up_mbps is not None and self.up_bytes == 0:
                raise ValueError("up_bytes must be > 0 when up_mbps is present")

        return self


class Radio(BaseModel):
    status: Literal["ok", "unavailable"]
    reason: str | None 
    observed_at_ms: int = Field(gt=0)
    rat: Literal["LTE", "NR", "WCDMA", "GSM", "TDSCDMA", "CDMA", "UNKNOWN"] | None
    mcc: str | None
    mnc: str | None
    rsrp: int | None = Field(ge=-160, le=0)
    rsrq: int | None = Field(ge=-50, le=30)
    sinr: int | None = Field(ge=-40, le=60)
    level: int | None = Field(ge=0, le=4)
    ci: int | None = Field(ge=0)
    pci: int | None = Field(ge=0, le=1007)
    tac: int | None = Field(ge=0, le=16777215)
    earfcn: int | None = Field(ge=0)

    @model_validator(mode="after")
    def check_status(self) -> Self:
        values = [self.rat, self.mcc, self.mnc, self.rsrp, self.rsrq, self.sinr,
                  self.level, self.ci, self.pci, self.tac, self.earfcn]
        if self.status == "unavailable":
            if self.reason is None:
                raise ValueError("reason is required when status is unavailable")
            if any(v is not None for v in values):
                raise ValueError("no radio values allowed when status is unavailable")
        else:
            if self.reason is not None:
                raise ValueError("reason must be null when status is ok")
            if self.rat is None:
                raise ValueError("rat is required when status is ok")
        return self

    

class NetPerf(BaseModel):
    started_at_ms: int = Field(gt=0)
    ended_at_ms: int = Field(gt=0)
    network_acquired: bool
    dns: DnsMetrics | None
    latency: LatencyMetrics | None
    throughput: ThroughputMetrics | None
    error: str | None

    @model_validator(mode="after")
    def check_consistency(self) -> Self:
        if self.ended_at_ms < self.started_at_ms:
            raise ValueError("ended_at_ms is before started_at_ms")
        blocks = (self.dns, self.latency, self.throughput)
        if not self.network_acquired and any(b is not None for b in blocks):
            raise ValueError("no measurements allowed when network_acquired is false")
        return self

    












