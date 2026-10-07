package com.example.monitoring.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record QueryInsight(
        @JsonPropertyDescription("The queryId of the sample this assessment is about, copied exactly") String queryId,
        @JsonPropertyDescription("LOW, MEDIUM, HIGH or CRITICAL") QueryRiskLevel riskLevel,
        @JsonPropertyDescription("Short English category such as FULL_SCAN, MISSING_INDEX, LARGE_SORT, LOCK_RISK, "
                + "UNBOUNDED_RESULT, HEAVY_WRITE, N_PLUS_ONE, OK") String category,
        @JsonPropertyDescription("Korean explanation of the problem, citing the statistics") String problem,
        @JsonPropertyDescription("Korean concrete fix such as a rewrite, an index or batching") String recommendation,
        @JsonPropertyDescription("A CREATE INDEX statement if an index would help, otherwise an empty string")
        String suggestedIndex
) {
}
