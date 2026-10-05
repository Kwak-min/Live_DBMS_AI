package com.example.monitoring.risk.policy;

import com.example.monitoring.risk.contract.RiskRule;
import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleOperator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Objects;

public record PolicyRuleWrite(
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictRuleId.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"CONNECTION_RATIO", "SLOW_QUERY_RATE"})
        RuleId ruleId,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictString.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String metricName,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictRuleOperator.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "GTE")
        RuleOperator operator,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictDecimal.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, exclusiveMinimum = true, minimum = "0")
        BigDecimal warningThreshold,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictDecimal.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, exclusiveMinimum = true, minimum = "0")
        BigDecimal criticalThreshold,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictDecimal.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Required for CONNECTION_RATIO and null for SLOW_QUERY_RATE")
        BigDecimal fatalThreshold,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictInteger.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "5", maximum = "300",
                description = "Multiple of five seconds")
        Integer sustainSeconds,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictInteger.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "5", maximum = "300",
                description = "Multiple of five seconds")
        Integer recoverySeconds,
        @JsonProperty(required = true)
        @JsonDeserialize(using = PolicyJson.StrictBoolean.class)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean enabled
) {
    public static PolicyRuleWrite from(RiskRule rule) {
        Objects.requireNonNull(rule, "rule");
        return new PolicyRuleWrite(
                rule.ruleId(),
                rule.metricName(),
                rule.operator(),
                rule.warningThreshold(),
                rule.criticalThreshold(),
                rule.fatalThreshold(),
                rule.sustainSeconds(),
                rule.recoverySeconds(),
                rule.enabled());
    }
}
