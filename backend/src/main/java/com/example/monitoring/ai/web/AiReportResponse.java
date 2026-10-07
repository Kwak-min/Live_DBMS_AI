package com.example.monitoring.ai.web;

import com.example.monitoring.ai.model.AiReportStatus;
import com.example.monitoring.ai.model.AiReportType;
import com.example.monitoring.ai.model.AiTriggerSource;
import com.example.monitoring.ai.model.DailyReport;
import com.example.monitoring.ai.model.QueryAnalysis;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "AI report. dailyReport is set only for a SUCCEEDED DAILY_REPORT, queryAnalysis only for a "
        + "SUCCEEDED QUERY_ANALYSIS. errorCode/errorMessage are set only when FAILED.")
public record AiReportResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiReportType type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long databaseConfigId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Target name at request time")
        String databaseName,
        @Schema(nullable = true, description = "Local date in the report time zone; DAILY_REPORT only")
        LocalDate reportDate,
        @Schema(nullable = true, description = "Inclusive UTC window start; DAILY_REPORT only") Instant windowStart,
        @Schema(nullable = true, description = "Exclusive UTC window end; DAILY_REPORT only") Instant windowEnd,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiReportStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiTriggerSource triggerSource,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant requestedAt,
        @Schema(nullable = true) Instant completedAt,
        @Schema(nullable = true, description = "AI_UNAVAILABLE, AI_REFUSED, AI_TRUNCATED, AI_RATE_LIMITED, "
                + "AI_AUTH_FAILED, AI_UPSTREAM_ERROR, AI_INVALID_OUTPUT, TARGET_UNREACHABLE, "
                + "CREDENTIALS_UNAVAILABLE, INTERRUPTED, INTERNAL_ERROR") String errorCode,
        @Schema(nullable = true) String errorMessage,
        @Schema(nullable = true) Long inputTokens,
        @Schema(nullable = true) Long outputTokens,
        @Schema(nullable = true) DailyReport dailyReport,
        @Schema(nullable = true) QueryAnalysis queryAnalysis
) {
}
