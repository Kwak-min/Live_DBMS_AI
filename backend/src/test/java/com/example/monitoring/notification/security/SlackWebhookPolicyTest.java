package com.example.monitoring.notification.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackWebhookPolicyTest {
    private final SlackWebhookPolicy policy = new SlackWebhookPolicy();

    @Test
    void acceptsExactSlackWebhookGrammar() {
        assertThat(policy.validate("https://hooks.slack.com/services/T000/B000/token_-1"))
                .isEqualTo(URI.create("https://hooks.slack.com/services/T000/B000/token_-1"));
        assertThat(policy.canonicalIdentity("HTTPS://HOOKS.SLACK.COM/services/T000/B000/Token_-1"))
                .isEqualTo("https://hooks.slack.com/services/T000/B000/Token_-1");
    }

    @Test
    void rejectsUserInfoQueryFragmentPortAndWrongPath() {
        for (String invalid : new String[] {
                "http://hooks.slack.com/services/T/B/X",
                "https://user@hooks.slack.com/services/T/B/X",
                "https://hooks.slack.com:443/services/T/B/X",
                "https://hooks.slack.com/services/T/B/X?secret=1",
                "https://hooks.slack.com/services/T/B/X#fragment",
                "https://hooks.slack.com.evil.test/services/T/B/X",
                "https://hooks.slack.com/services/T/B",
                "https://hooks.slack.com/services/T/B/X/",
                "https://hooks.slack.com/services/T/B/%2f",
                "https://hooks.slack.com/services/T/../X"
        }) {
            assertThatThrownBy(() -> policy.validate(invalid))
                    .as(invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
