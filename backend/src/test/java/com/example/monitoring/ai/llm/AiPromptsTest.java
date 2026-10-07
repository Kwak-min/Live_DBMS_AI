package com.example.monitoring.ai.llm;

import com.example.monitoring.ai.model.DailyStats;
import com.example.monitoring.ai.model.HourlyStat;
import com.example.monitoring.ai.model.IncidentSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiPromptsTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void dailyPromptShowsTimestampsInTheReportTimeZone() {
        Instant failure = Instant.parse("2026-10-05T18:00:15Z"); // KST 2026-10-06 03:00:15
        DailyStats stats = new DailyStats(Instant.parse("2026-10-05T15:00:00Z"), Instant.parse("2026-10-06T15:00:00Z"),
                1, 0, 0, 1, 0.0, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, Map.of("CONNECT_TIMEOUT", 1L), 1,
                List.of(new IncidentSummary("id", "CONNECTION_FAILURE", "CRITICAL", "RESOLVED", failure, null,
                        "connectionStatus", null, null, "msg 2026-10-05T18:00:15Z")),
                List.of(new HourlyStat(Instant.parse("2026-10-05T18:00:00Z"), 1, 1, null, null, null, null)));

        String prompt = AiPrompts.dailyUser(mapper, "db", LocalDate.parse("2026-10-06"), "Asia/Seoul", stats, null);

        assertThat(prompt).contains("\"openedAt\" : \"2026-10-06T03:00:15+09:00\"",
                "\"hourStart\" : \"2026-10-06T03:00:00+09:00\"",
                "\"windowStart\" : \"2026-10-06T00:00:00+09:00\"");
        assertThat(prompt).doesNotContain("\"2026-10-05T18:00:15Z\"");
        // 자유 텍스트 안의 시각은 바꾸지 않는다.
        assertThat(prompt).contains("msg 2026-10-05T18:00:15Z");
    }
}
