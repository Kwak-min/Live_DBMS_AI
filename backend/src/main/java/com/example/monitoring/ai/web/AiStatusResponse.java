package com.example.monitoring.ai.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Whether AI generation is available. Stored reports stay readable when it is not.")
public record AiStatusResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "true when AI is enabled and an API key is configured") boolean available,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "IANA time zone that defines a report day") String timeZone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "true when daily reports are generated automatically") boolean dailyReportScheduled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Seconds between manual generation requests per target and type") int requestCooldownSeconds
) {
}
