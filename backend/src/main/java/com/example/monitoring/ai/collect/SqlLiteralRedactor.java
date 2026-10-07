package com.example.monitoring.ai.collect;

/**
 * SQL 문장에서 값(문자열·숫자·16진수 리터럴)과 주석을 지워 '?'로 바꾼다. 식별자(`x`, 이름 속 숫자)는 남긴다.
 * PROCESSLIST 원문에는 사용자 데이터가 들어 있을 수 있으므로 외부 AI로 보내기 전에 반드시 거친다.
 */
public final class SqlLiteralRedactor {

    public static final int MAX_LENGTH = 4000;

    private SqlLiteralRedactor() {
    }

    public static String redact(String sql) {
        if (sql == null) return null;
        StringBuilder out = new StringBuilder(Math.min(sql.length(), MAX_LENGTH + 16));
        int length = sql.length();
        int i = 0;
        while (i < length && out.length() < MAX_LENGTH) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                out.append('?');
            } else if (c == '`') {
                int end = skipQuoted(sql, i, '`');
                out.append(sql, i, end);
                i = end;
            } else if (c == '-' && next == '-' || c == '#') {
                while (i < length && sql.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                out.append(' ');
            } else if ((c == 'x' || c == 'X' || c == 'b' || c == 'B') && next == '\'' && !identifierBefore(out)) {
                i = skipQuoted(sql, i + 1, '\'');
                out.append('?');
            } else if (c == '0' && (next == 'x' || next == 'X') && !identifierBefore(out)) {
                i += 2;
                while (i < length && Character.digit(sql.charAt(i), 16) >= 0) i++;
                out.append('?');
            } else if (Character.isDigit(c) && !identifierBefore(out)) {
                while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                out.append('?');
            } else {
                out.append(c);
                i++;
            }
        }
        String result = out.toString().replaceAll("\\s+", " ").trim();
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
    }

    /** 닫는 따옴표 다음 위치. 백슬래시 이스케이프와 두 번 겹친 따옴표를 건너뛴다. */
    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
            } else if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return sql.length();
    }

    private static boolean identifierBefore(StringBuilder out) {
        if (out.isEmpty()) return false;
        char previous = out.charAt(out.length() - 1);
        return Character.isLetterOrDigit(previous) || previous == '_' || previous == '$';
    }
}
