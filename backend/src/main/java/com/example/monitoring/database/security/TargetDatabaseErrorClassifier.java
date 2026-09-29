package com.example.monitoring.database.security;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;

public final class TargetDatabaseErrorClassifier {
    private TargetDatabaseErrorClassifier() { }

    public static SafeError classify(SQLException exception, boolean connected) {
        if (connected) return new SafeError("QUERY_FAILED", "대상 DB 진단 쿼리를 실행하지 못했습니다.");
        if (exception instanceof SQLInvalidAuthorizationSpecException || startsWith(exception.getSQLState(), "28"))
            return new SafeError("AUTH_FAILED", "대상 DB 인증에 실패했습니다.");
        if (hasCause(exception, SocketTimeoutException.class))
            return new SafeError("CONNECT_TIMEOUT", "대상 DB 연결 시간이 초과되었습니다.");
        if (hasCause(exception, ConnectException.class))
            return new SafeError("CONNECTION_REFUSED", "대상 DB가 연결을 거부했습니다.");
        return new SafeError("UNKNOWN", "대상 DB에 연결하지 못했습니다.");
    }

    private static boolean startsWith(String value, String prefix) { return value != null && value.startsWith(prefix); }
    private static boolean hasCause(Throwable value, Class<? extends Throwable> type) {
        for (Throwable current = value; current != null; current = current.getCause()) if (type.isInstance(current)) return true;
        return false;
    }

    public record SafeError(String code, String message) { }
}
