package com.example.monitoring.notification.security;

import java.util.regex.Pattern;

public final class NotificationContentPolicy {
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\bauthorization\\s*:\\s*\\S+|\\bbearer\\s+\\S+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:access[_ -]?token|refresh[_ -]?token|auth[_ -]?token|token|password|passwd|pwd|"
                    + "secret|api[_ -]?key|client[_ -]?secret|username|user)\\s*[:=]\\s*\\S+");
    private static final Pattern ABSOLUTE_ADDRESS = Pattern.compile(
            "(?i)\\b(?:jdbc:|https?://|postgresql://|mariadb://|mysql://)\\S+");
    private static final Pattern ACCOUNT = Pattern.compile(
            "(?i)(?<![\\w.-])[\\w.!#$%&'*+/=?^`{|}~-]+@[a-z0-9]"
                    + "(?:[a-z0-9.-]*[a-z0-9])?(?![\\w.-])");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9])(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}"
                    + "(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9])");
    private static final Pattern HOST_PORT = Pattern.compile(
            "(?i)\\b(?:localhost|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+):[0-9]{1,5}\\b");
    private static final Pattern SQL = Pattern.compile(
            "(?is)\\b(select\\s+.+\\s+from|insert\\s+into|update\\s+.+\\s+set|delete\\s+from|"
                    + "drop\\s+(?:table|database)|alter\\s+table)\\b");

    private NotificationContentPolicy() { }

    public static void requireSafe(String... values) {
        for (String value : values) {
            if (value == null || AUTHORIZATION.matcher(value).find()
                    || SECRET_ASSIGNMENT.matcher(value).find()
                    || ABSOLUTE_ADDRESS.matcher(value).find()
                    || ACCOUNT.matcher(value).find()
                    || IPV4.matcher(value).find()
                    || HOST_PORT.matcher(value).find()
                    || SQL.matcher(value).find()) {
                throw new IllegalArgumentException("Notification content contains a forbidden value.");
            }
        }
    }
}
