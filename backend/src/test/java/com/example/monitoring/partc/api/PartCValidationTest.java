package com.example.monitoring.partc.api;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.common.api.FieldErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartCValidationTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:34:56.789Z");
    private final PartCQueryValidator validator =
            new PartCQueryValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsSafeIdUuidPageAndExactEnumValues() {
        assertThat(validator.requiredId("1", "databaseConfigId")).isEqualTo(1L);
        assertThat(validator.requiredId(Long.toString(ApiId.MAX_SAFE_INTEGER), "databaseConfigId"))
                .isEqualTo(ApiId.MAX_SAFE_INTEGER);
        assertThat(validator.requiredUuid("984b0ae3-37e9-46b1-a709-87bfb95b9a1a", "incidentId"))
                .isEqualTo(UUID.fromString("984b0ae3-37e9-46b1-a709-87bfb95b9a1a"));
        assertThat(validator.page(null, null)).isEqualTo(new PartCPage(0, 20));
        assertThat(validator.page("10000", "100")).isEqualTo(new PartCPage(10_000, 100));
        assertThat(validator.optionalEnum("OPEN", SampleStatus.class, "status"))
                .isEqualTo(SampleStatus.OPEN);
        assertThat(validator.optionalEnum(null, SampleStatus.class, "status")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidQueries")
    void rejectsUnsafeAndPartialQueries(String description, ThrowingCall call, String field, String fieldCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(failure.getCode()).isEqualTo("VALIDATION_ERROR");
                    assertThat(failure.getMessage()).isEqualTo("요청 값을 확인해 주세요.");
                    assertThat(failure.getFieldErrors())
                            .extracting(FieldErrorResponse::field, FieldErrorResponse::code)
                            .containsExactly(org.assertj.core.groups.Tuple.tuple(field, fieldCode));
                });
    }

    private static Stream<Arguments> invalidQueries() {
        PartCQueryValidator validator = new PartCQueryValidator(Clock.fixed(NOW, ZoneOffset.UTC));
        return Stream.of(
                Arguments.of("zero id", (ThrowingCall) () -> validator.requiredId("0", "databaseConfigId"),
                        "databaseConfigId", "OUT_OF_RANGE"),
                Arguments.of("unsafe id", (ThrowingCall) () -> validator.requiredId("9007199254740992", "databaseConfigId"),
                        "databaseConfigId", "OUT_OF_RANGE"),
                Arguments.of("id overflow", (ThrowingCall) () -> validator.requiredId("999999999999999999999", "databaseConfigId"),
                        "databaseConfigId", "OUT_OF_RANGE"),
                Arguments.of("non-numeric id", (ThrowingCall) () -> validator.requiredId("1.0", "databaseConfigId"),
                        "databaseConfigId", "INVALID_FORMAT"),
                Arguments.of("uppercase uuid", (ThrowingCall) () -> validator.requiredUuid(
                                "984B0AE3-37E9-46B1-A709-87BFB95B9A1A", "incidentId"),
                        "incidentId", "INVALID_FORMAT"),
                Arguments.of("invalid uuid", (ThrowingCall) () -> validator.requiredUuid("not-a-uuid", "incidentId"),
                        "incidentId", "INVALID_FORMAT"),
                Arguments.of("negative page", (ThrowingCall) () -> validator.page("-1", "20"),
                        "page", "OUT_OF_RANGE"),
                Arguments.of("page too large", (ThrowingCall) () -> validator.page("10001", "20"),
                        "page", "OUT_OF_RANGE"),
                Arguments.of("size zero", (ThrowingCall) () -> validator.page("0", "0"),
                        "size", "OUT_OF_RANGE"),
                Arguments.of("size too large", (ThrowingCall) () -> validator.page("0", "101"),
                        "size", "OUT_OF_RANGE"),
                Arguments.of("case drift enum", (ThrowingCall) () -> validator.optionalEnum(
                                "open", SampleStatus.class, "status"),
                        "status", "INVALID_VALUE"),
                Arguments.of("one-sided start", (ThrowingCall) () -> validator.window(
                                "2026-09-27T12:34:56.789Z", null),
                        "end", "REQUIRED"),
                Arguments.of("one-sided end", (ThrowingCall) () -> validator.window(
                                null, "2026-09-28T12:34:56.789Z"),
                        "start", "REQUIRED"),
                Arguments.of("equal window", (ThrowingCall) () -> validator.window(
                                "2026-09-28T12:34:56.789Z", "2026-09-28T12:34:56.789Z"),
                        "start", "INVALID_VALUE"),
                Arguments.of("reversed window", (ThrowingCall) () -> validator.window(
                                "2026-09-28T12:34:56.789Z", "2026-09-27T12:34:56.789Z"),
                        "start", "INVALID_VALUE"),
                Arguments.of("over 30 days", (ThrowingCall) () -> validator.window(
                                "2026-08-29T12:34:56.788Z", "2026-09-28T12:34:56.789Z"),
                        "start", "OUT_OF_RANGE"),
                Arguments.of("non-UTC timestamp", (ThrowingCall) () -> validator.window(
                                "2026-09-27T21:34:56.789+09:00", "2026-09-28T21:34:56.789+09:00"),
                        "start", "INVALID_FORMAT"),
                Arguments.of("timestamp without milliseconds", (ThrowingCall) () -> validator.window(
                                "2026-09-27T12:34:56Z", "2026-09-28T12:34:56Z"),
                        "start", "INVALID_FORMAT"),
                Arguments.of("future forbidden", (ThrowingCall) () -> validator.window(
                                "2026-09-28T12:34:56.789Z", "2026-09-28T12:34:56.790Z", true),
                        "end", "INVALID_VALUE")
        );
    }

    private enum SampleStatus { OPEN, RESOLVED }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
