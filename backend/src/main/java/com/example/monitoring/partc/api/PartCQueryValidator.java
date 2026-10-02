package com.example.monitoring.partc.api;

import com.example.monitoring.common.api.ApiException;
import com.example.monitoring.common.api.ApiId;
import com.example.monitoring.common.api.FieldErrorResponse;
import com.example.monitoring.common.config.UtcInstantJacksonConfig;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class PartCQueryValidator {

    private static final BigInteger MAX_SAFE_ID = BigInteger.valueOf(ApiId.MAX_SAFE_INTEGER);
    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    private static final Duration MAX_WINDOW = Duration.ofDays(30);
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]+");
    private static final Pattern LOWERCASE_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern UTC_MILLIS = Pattern.compile(
            "[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])"
                    + "T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]\\.[0-9]{3}Z");

    private final Clock clock;

    public PartCQueryValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public long requiredId(String raw, String field) {
        if (raw == null) {
            throw invalid(field, "REQUIRED", field + "은(는) 필수입니다.");
        }
        BigInteger value = integer(raw, field);
        if (value.signum() < 1 || value.compareTo(MAX_SAFE_ID) > 0) {
            throw invalid(field, "OUT_OF_RANGE", field + "은(는) 1~9007199254740991 범위여야 합니다.");
        }
        return value.longValueExact();
    }

    public Long optionalId(String raw, String field) {
        return raw == null ? null : requiredId(raw, field);
    }

    public UUID requiredUuid(String raw, String field) {
        if (raw == null) {
            throw invalid(field, "REQUIRED", field + "은(는) 필수입니다.");
        }
        if (!LOWERCASE_UUID.matcher(raw).matches()) {
            throw invalid(field, "INVALID_FORMAT", field + "은(는) 소문자 UUID 형식이어야 합니다.");
        }
        try {
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equals(raw)) {
                throw invalid(field, "INVALID_FORMAT", field + "은(는) 소문자 UUID 형식이어야 합니다.");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw invalid(field, "INVALID_FORMAT", field + "은(는) 소문자 UUID 형식이어야 합니다.");
        }
    }

    public UUID optionalUuid(String raw, String field) {
        return raw == null ? null : requiredUuid(raw, field);
    }

    public <E extends Enum<E>> E optionalEnum(String raw, Class<E> enumType, String field) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, raw);
        } catch (IllegalArgumentException exception) {
            throw invalid(field, "INVALID_VALUE", field + " 값이 허용된 enum이 아닙니다.");
        }
    }

    public PartCPage page(String rawPage, String rawSize) {
        int page = boundedInt(rawPage, "page", 0, 10_000, 0);
        int size = boundedInt(rawSize, "size", 1, 100, 20);
        return new PartCPage(page, size);
    }

    public PartCQueryWindow window(String rawStart, String rawEnd) {
        return window(rawStart, rawEnd, false);
    }

    public PartCQueryWindow window(String rawStart, String rawEnd, boolean rejectFutureEnd) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (rawStart == null && rawEnd == null) {
            return new PartCQueryWindow(now.minus(DEFAULT_WINDOW), now);
        }
        if (rawStart == null) {
            throw invalid("start", "REQUIRED", "start와 end는 함께 입력해야 합니다.");
        }
        if (rawEnd == null) {
            throw invalid("end", "REQUIRED", "start와 end는 함께 입력해야 합니다.");
        }

        Instant start = utcInstant(rawStart, "start");
        Instant end = utcInstant(rawEnd, "end");
        if (!start.isBefore(end)) {
            throw invalid("start", "INVALID_VALUE", "start는 end보다 이전이어야 합니다.");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw invalid("start", "OUT_OF_RANGE", "조회 기간은 최대 30일입니다.");
        }
        if (rejectFutureEnd && end.isAfter(now)) {
            throw invalid("end", "INVALID_VALUE", "end는 현재 시각 이후일 수 없습니다.");
        }
        return new PartCQueryWindow(start, end);
    }

    public Instant utcInstant(String raw, String field) {
        if (raw == null) {
            throw invalid(field, "REQUIRED", field + "은(는) 필수입니다.");
        }
        if (!UTC_MILLIS.matcher(raw).matches()) {
            throw invalid(field, "INVALID_FORMAT", field + "은(는) UTC 밀리초 형식이어야 합니다.");
        }
        try {
            Instant parsed = Instant.parse(raw);
            if (!UtcInstantJacksonConfig.format(parsed).equals(raw)) {
                throw invalid(field, "INVALID_FORMAT", field + "은(는) UTC 밀리초 형식이어야 합니다.");
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw invalid(field, "INVALID_FORMAT", field + "은(는) UTC 밀리초 형식이어야 합니다.");
        }
    }

    private int boundedInt(String raw, String field, int minimum, int maximum, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        BigInteger value = integer(raw, field);
        if (value.compareTo(BigInteger.valueOf(minimum)) < 0
                || value.compareTo(BigInteger.valueOf(maximum)) > 0) {
            throw invalid(field, "OUT_OF_RANGE",
                    field + "은(는) " + minimum + "~" + maximum + " 범위여야 합니다.");
        }
        return value.intValueExact();
    }

    private BigInteger integer(String raw, String field) {
        if (!INTEGER.matcher(raw).matches()) {
            throw invalid(field, "INVALID_FORMAT", field + "은(는) 정수여야 합니다.");
        }
        return new BigInteger(raw);
    }

    private ApiException invalid(String field, String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 값을 확인해 주세요.",
                List.of(new FieldErrorResponse(field, code, message)));
    }
}
