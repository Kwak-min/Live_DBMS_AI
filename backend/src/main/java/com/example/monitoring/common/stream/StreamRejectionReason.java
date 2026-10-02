package com.example.monitoring.common.stream;

import java.util.regex.Pattern;

final class StreamRejectionReason {

    private static final Pattern SAFE_REASON = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private StreamRejectionReason() {
    }

    static String requireValid(String value) {
        if (value == null || !SAFE_REASON.matcher(value).matches()) {
            throw new IllegalArgumentException("reasonCode must be an uppercase diagnostic code");
        }
        return value;
    }
}
