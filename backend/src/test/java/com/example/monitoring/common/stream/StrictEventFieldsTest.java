package com.example.monitoring.common.stream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictEventFieldsTest {

    private final StrictEventFields fields = new StrictEventFields(
            new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void readsCanonicalEnvelopeValuesAndIgnoresUnknownOptionalFields() {
        ObjectNode root = fields.parseObject(bytes("""
                {"schemaVersion":1,
                 "eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83",
                 "publishedAt":"2026-10-02T03:04:05.678Z",
                 "databaseConfigId":12,
                 "unknownOptional":{"future":true}}
                """));

        assertThat(fields.requiredInt(root, "schemaVersion")).isOne();
        assertThat(fields.requiredUuid(root, "eventId"))
                .isEqualTo(UUID.fromString("9bfab7ee-221a-4fb4-9507-6fd6f4df7e83"));
        assertThat(fields.requiredInstant(root, "publishedAt"))
                .isEqualTo(Instant.parse("2026-10-02T03:04:05.678Z"));
        assertThat(fields.requiredSafeLong(root, "databaseConfigId")).isEqualTo(12L);
    }

    @Test
    void rejectsDuplicateKeysTrailingTokensAndMalformedUtf8() {
        assertReason(
                "{\"eventId\":\"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83\",\"eventId\":null}",
                "DUPLICATE_JSON_KEY");
        assertReason("{} {}", "INVALID_JSON");
        assertThatThrownBy(() -> fields.parseObject(new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo("INVALID_UTF8"));
    }

    @Test
    void rejectsNoncanonicalTimeUuidAndUnsafeIdWithBestEffortEventId() {
        ObjectNode root = fields.parseObject(bytes("""
                {"eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83",
                 "publishedAt":"2026-10-02T03:04:05Z",
                 "sourceEventId":"9BFAB7EE-221A-4FB4-9507-6FD6F4DF7E83",
                 "databaseConfigId":9007199254740992}
                """));

        assertInvalid(root, () -> fields.requiredInstant(root, "publishedAt"), "INVALID_TIMESTAMP");
        assertInvalid(root, () -> fields.requiredUuid(root, "sourceEventId"), "INVALID_UUID");
        assertInvalid(root, () -> fields.requiredSafeLong(root, "databaseConfigId"), "INVALID_NUMERIC_VALUE");
    }

    @Test
    void preservesLargeFiniteDecimalsAndRejectsNonfiniteTreeValues() {
        ObjectNode root = fields.parseObject(bytes("""
                {"eventId":"9bfab7ee-221a-4fb4-9507-6fd6f4df7e83","metricValue":1e999}
                """));

        assertThat(fields.requiredNullableNonNegativeDecimal(root, "metricValue"))
                .isEqualByComparingTo(new BigDecimal("1e999"));

        root.put("metricValue", Double.POSITIVE_INFINITY);
        assertInvalid(
                root,
                () -> fields.requiredNullableNonNegativeDecimal(root, "metricValue"),
                "INVALID_NUMERIC_VALUE");
    }

    @Test
    void countsBoundedTextInUnicodeCodePoints() {
        String supplementary = "\uD83D\uDE00";
        ObjectNode root = fields.parseObject(bytes("{}"));
        String maximum = supplementary.repeat(100);
        root.put("databaseName", maximum);

        assertThat(fields.requiredText(root, "databaseName", 1, 100)).isEqualTo(maximum);

        root.put("databaseName", supplementary.repeat(101));
        assertInvalid(
                root,
                () -> fields.requiredText(root, "databaseName", 1, 100),
                "INVALID_FIELD_VALUE");
    }

    private void assertReason(String json, String reason) {
        assertThatThrownBy(() -> fields.parseObject(bytes(json)))
                .isInstanceOfSatisfying(InvalidStreamRecordException.class,
                        exception -> assertThat(exception.reasonCode()).isEqualTo(reason));
    }

    private void assertInvalid(
            ObjectNode root,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
            String reason
    ) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(InvalidStreamRecordException.class, exception -> {
                    assertThat(exception.reasonCode()).isEqualTo(reason);
                    assertThat(exception.eventId()).isEqualTo(fields.bestEffortEventId(root));
                });
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
