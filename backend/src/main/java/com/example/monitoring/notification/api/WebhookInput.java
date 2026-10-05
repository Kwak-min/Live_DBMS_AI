package com.example.monitoring.notification.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WebhookInput(
        @JsonProperty(required = true)
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @NotBlank @Schema(maxLength = 100) String name,
        @JsonProperty(required = true)
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @NotBlank @Schema(allowableValues = {"SLACK"}) String provider,
        @JsonProperty(required = true)
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @NotBlank @Schema(maxLength = 2_048) String url,
        @JsonProperty(required = true)
        @JsonDeserialize(using = NotificationJson.StrictBoolean.class)
        @NotNull Boolean enabled
) {
}
