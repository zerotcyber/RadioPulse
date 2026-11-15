from typing import Self
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


    












