package com.example.monitoring.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Contract shared by all REST error responses. */
@Schema(description = "Common REST error. The message is safe for users and never contains credentials or SQL details.")
public record ApiErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Server-generated request UUID") String requestId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Empty array when no field-specific errors") List<FieldErrorResponse> fieldErrors
) {
    public ApiErrorResponse {
        fieldErrors = List.copyOf(fieldErrors == null ? List.of() : fieldErrors);
    }
}
