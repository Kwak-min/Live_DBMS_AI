package com.example.monitoring.realtime.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterPayloadSanitizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DeadLetterPayloadSanitizer sanitizer = new DeadLetterPayloadSanitizer(mapper);

    @Test
    void recursivelyRedactsSecretFieldsInValidJson() throws Exception {
        String raw = """
                {
                  "password":"TOP_SECRET_42",
                  "nested":{"api-key":"API_SECRET_42","databaseName":"orders"},
                  "items":[{"authorization":"Bearer TOKEN_42"}]
                }
                """;

        JsonNode sanitized = mapper.readTree(sanitizer.sanitize(bytes(raw)));

        assertThat(sanitized.get("password").textValue()).isEqualTo("[REDACTED]");
        assertThat(sanitized.at("/nested/api-key").textValue()).isEqualTo("[REDACTED]");
        assertThat(sanitized.at("/nested/databaseName").textValue()).isEqualTo("orders");
        assertThat(sanitized.at("/items/0/authorization").textValue()).isEqualTo("[REDACTED]");
        assertThat(sanitized.toString())
                .doesNotContain("TOP_SECRET_42", "API_SECRET_42", "TOKEN_42");
    }

    @Test
    void replacesUnparseableAndNonObjectPayloadsWithConstantMarker() {
        assertThat(sanitizer.sanitize(bytes("password=TOP_SECRET_42")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("db.password: TOP_SECRET_42")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("{\"password\":\"TOP_SECRET_42")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(new byte[0]))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("\"TOP_SECRET_42\"")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("[\"TOP_SECRET_42\"]")))
                .isEqualTo("[UNPARSEABLE_JSON]");
        assertThat(sanitizer.sanitize(bytes("null")))
                .isEqualTo("[UNPARSEABLE_JSON]");
    }

    @Test
    void marksInvalidUtf8WithoutRetainingBytes() {
        assertThat(sanitizer.sanitize(new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isEqualTo("[INVALID_UTF8]");
    }

    @Test
    void boundsSanitizedPayloadTo64KiBUtf8() {
        String raw = "{\"note\":\"" + "가".repeat(30_000) + "\"}";

        byte[] sanitized = sanitizer.sanitize(bytes(raw)).getBytes(StandardCharsets.UTF_8);

        assertThat(sanitized.length).isLessThanOrEqualTo(64 * 1024);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
