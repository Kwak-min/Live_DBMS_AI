package com.example.monitoring.risk.policy;

import com.example.monitoring.risk.contract.RuleId;
import com.example.monitoring.risk.contract.RuleOperator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.math.BigDecimal;

final class PolicyJson {

    private PolicyJson() {
    }

    public static final class StrictLong extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }

    public static final class StrictInteger extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }

    public static final class StrictDecimal extends JsonDeserializer<BigDecimal> {
        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)
                    && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
            }
            return parser.getDecimalValue();
        }
    }

    public static final class StrictBoolean extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_TRUE) && !parser.hasToken(JsonToken.VALUE_FALSE)) {
                return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
            }
            return parser.getBooleanValue();
        }
    }

    public static final class StrictString extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }

    public static final class StrictRuleId extends JsonDeserializer<RuleId> {
        @Override
        public RuleId deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (RuleId) context.handleUnexpectedToken(RuleId.class, parser);
            }
            String value = parser.getText();
            try {
                return RuleId.valueOf(value);
            } catch (IllegalArgumentException exception) {
                return (RuleId) context.handleWeirdStringValue(
                        RuleId.class, value, "Unsupported policy ruleId");
            }
        }
    }

    public static final class StrictRuleOperator extends JsonDeserializer<RuleOperator> {
        @Override
        public RuleOperator deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (RuleOperator) context.handleUnexpectedToken(RuleOperator.class, parser);
            }
            String value = parser.getText();
            try {
                return RuleOperator.valueOf(value);
            } catch (IllegalArgumentException exception) {
                return (RuleOperator) context.handleWeirdStringValue(
                        RuleOperator.class, value, "Unsupported policy rule operator");
            }
        }
    }
}
