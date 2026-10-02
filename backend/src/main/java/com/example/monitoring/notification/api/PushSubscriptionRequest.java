package com.example.monitoring.notification.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PushSubscriptionRequest(
        @JsonProperty(required = true)
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @NotBlank @Schema(maxLength = 2_048) String endpoint,
        @JsonProperty(value = "expirationTime", required = true)
        @JsonDeserialize(using = NotificationJson.StrictLong.class)
        @Schema(type = "integer", format = "int64",
                requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        Long expirationTime,
        @JsonProperty(required = true) @NotNull @Valid PushKeys keys
) {
}
