package com.example.monitoring.ai.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record AiFinding(
        @JsonPropertyDescription("INFO, WARNING or CRITICAL") InsightSeverity severity,
        @JsonPropertyDescription("Short Korean headline, under 60 characters") String title,
        @JsonPropertyDescription("Korean explanation that cites the concrete numbers it is based on") String detail
) {
}
