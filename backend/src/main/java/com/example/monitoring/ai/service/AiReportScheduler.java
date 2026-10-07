package com.example.monitoring.ai.service;

import com.example.monitoring.ai.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 매일 정해진 시각에 전날 일일 보고서를 만들고 보관 기간이 지난 AI 보고서를 지운다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "monitoring.ai.enabled", havingValue = "true")
public class AiReportScheduler {

    private final AiReportService service;
    private final AiProperties properties;

    public AiReportScheduler(AiReportService service, AiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(cron = "${monitoring.ai.daily-report-cron:0 10 0 * * *}",
            zone = "${monitoring.ai.daily-report-zone:UTC}")
    public void run() {
        try {
            int deleted = service.deleteExpired();
            if (deleted > 0) log.info("Deleted expired AI reports. count={}", deleted);
        } catch (RuntimeException e) {
            log.warn("AI report retention cleanup failed. exceptionType={}", e.getClass().getSimpleName());
        }
        if (!properties.dailyReportScheduleEnabled()) return;
        LocalDate yesterday = LocalDate.now(properties.zone()).minusDays(1);
        int scheduled = service.scheduleDailyReports(yesterday);
        log.info("Scheduled daily AI reports. reportDate={}, count={}", yesterday, scheduled);
    }
}
