package com.example.monitoring.dto;

import com.example.monitoring.domain.TargetDbStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DbPingResponseDto {
    private Long databaseConfigId;
    private TargetDbStatus status;
    private String version;
    private Long responseTimeMs;
    private LocalDateTime timestamp;
    private String errorMessage;
}
