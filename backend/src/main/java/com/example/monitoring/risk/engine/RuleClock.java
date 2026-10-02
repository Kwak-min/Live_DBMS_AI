package com.example.monitoring.risk.engine;

import com.example.monitoring.risk.contract.RuleId;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record RuleClock(
        RuleId ruleId,
        Instant warningCandidateSince,
        Instant criticalCandidateSince,
        Instant fatalCandidateSince,
        Instant recoverySince,
        Instant lastObservedAt,
        Long lastMetricId
) {
    public RuleClock {
        Objects.requireNonNull(ruleId, "ruleId");
        warningCandidateSince = millis(warningCandidateSince);
        criticalCandidateSince = millis(criticalCandidateSince);
        fatalCandidateSince = millis(fatalCandidateSince);
        recoverySince = millis(recoverySince);
        lastObservedAt = millis(lastObservedAt);
        if (lastMetricId != null && (lastMetricId < 1 || lastMetricId > RiskState.MAX_SAFE_INTEGER)) {
            throw new IllegalArgumentException("lastMetricId must be a positive JavaScript-safe integer");
        }
    }

    public static RuleClock empty(RuleId ruleId) {
        return new RuleClock(ruleId, null, null, null, null, null, null);
    }

    RuleClock resetAt(Instant observedAt, long metricId) {
        return new RuleClock(ruleId, null, null, null, null, observedAt, metricId);
    }

    private static Instant millis(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }
}
