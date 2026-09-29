package com.example.monitoring.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** A validation failure for one request field. */
public record FieldErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String field,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"REQUIRED", "INVALID_FORMAT", "OUT_OF_RANGE", "UNKNOWN_FIELD", "INVALID_VALUE"}) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {
}
