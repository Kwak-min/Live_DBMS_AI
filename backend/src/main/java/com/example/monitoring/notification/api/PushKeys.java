package com.example.monitoring.notification.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PushKeys(
        @JsonProperty(required = true)
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @NotBlank @Size(max = 512) String p256dh,
        @JsonProperty(required = true)
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @NotBlank @Size(max = 512) String auth
) {
}
