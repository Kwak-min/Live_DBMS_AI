package com.example.monitoring.risk.policy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;

import java.util.List;

public record PolicyWrite(
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictLong.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Current positive JavaScript-safe policy version; mismatch returns 409",
                minimum = "1", maximum = "9007199254740990")
        Long version,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictInteger.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "30", maximum = "300")
        Integer staleAfterSeconds,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictInteger.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "60", maximum = "3600")
        Integer notificationCooldownSeconds,
        @JsonProperty(required = true)
        @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                        description = "Complete two-rule replacement"),
                minItems = 2, maxItems = 2,
                schema = @Schema(implementation = PolicyRuleWrite.class,
                        description = "Exactly CONNECTION_RATIO and SLOW_QUERY_RATE once each"))
        List<PolicyRuleWrite> rules
) {
    public PolicyWrite {
        rules = rules == null
                ? null
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(rules));
    }
}
