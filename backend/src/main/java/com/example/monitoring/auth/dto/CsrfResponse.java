package com.example.monitoring.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CsrfResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Send this in X-CSRF-Token for auth mutations; keep only in memory") String csrfToken) {
}
