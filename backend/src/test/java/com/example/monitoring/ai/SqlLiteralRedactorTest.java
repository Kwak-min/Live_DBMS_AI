package com.example.monitoring.ai;

import com.example.monitoring.ai.collect.SqlLiteralRedactor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLiteralRedactorTest {

    @Test
    void replacesStringNumberAndHexLiteralsButKeepsIdentifiers() {
        String redacted = SqlLiteralRedactor.redact(
                "SELECT * FROM t1 WHERE email = 'kim@example.com' AND age > 30 AND `col2` = \"x\" "
                        + "AND token = 0xDEADBEEF AND bits = b'1010' AND hex = X'0A' LIMIT 10");

        assertThat(redacted).isEqualTo("SELECT * FROM t1 WHERE email = ? AND age > ? AND `col2` = ? "
                + "AND token = ? AND bits = ? AND hex = ? LIMIT ?");
        assertThat(redacted).doesNotContain("kim@example.com", "DEADBEEF", "30");
    }

    @Test
    void handlesEscapesDoubledQuotesAndComments() {
        String redacted = SqlLiteralRedactor.redact(
                "UPDATE users SET note = 'it''s \\'secret\\'' /* card 4111 */ WHERE id = 7 -- pw=hunter2\n"
                        + "AND name = 'a' # trailing secret");

        assertThat(redacted).isEqualTo("UPDATE users SET note = ? WHERE id = ? AND name = ?");
        assertThat(redacted).doesNotContain("secret", "4111", "hunter2");
    }

    @Test
    void keepsPlaceholdersAndDecimalsBecomeSinglePlaceholder() {
        assertThat(SqlLiteralRedactor.redact("SELECT price * 1.15 FROM orders WHERE id IN (?, ?)"))
                .isEqualTo("SELECT price * ? FROM orders WHERE id IN (?, ?)");
    }

    @Test
    void unterminatedLiteralIsRemovedAndLengthIsBounded() {
        assertThat(SqlLiteralRedactor.redact("SELECT 'unterminated secret")).isEqualTo("SELECT ?");
        assertThat(SqlLiteralRedactor.redact("SELECT " + "a,".repeat(5_000)).length())
                .isLessThanOrEqualTo(SqlLiteralRedactor.MAX_LENGTH);
        assertThat(SqlLiteralRedactor.redact(null)).isNull();
    }
}
