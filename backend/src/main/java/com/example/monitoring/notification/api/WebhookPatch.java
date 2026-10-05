package com.example.monitoring.notification.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

public record WebhookPatch(
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @Schema(maxLength = 100) String name,
        @JsonDeserialize(using = NotificationJson.StrictString.class)
        @Schema(maxLength = 2_048) String url,
        @JsonDeserialize(using = NotificationJson.StrictBoolean.class)
        Boolean enabled
) {
}
