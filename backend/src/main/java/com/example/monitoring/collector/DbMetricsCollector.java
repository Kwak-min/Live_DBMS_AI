package com.example.monitoring.collector;

import com.example.monitoring.database.port.CollectorTarget;
import com.example.monitoring.domain.MetricData;

public interface DbMetricsCollector {

    /**
     * 대상에 접속해 한 번 수집한다. 실패도 예외 대신 실패 스냅샷으로 반환한다.
     * 반환값에는 대상 참조·configVersion·lastSuccessAt이 없으며 기록 단계에서 채운다.
     */
    MetricData collectMetrics(CollectorTarget target);
}
