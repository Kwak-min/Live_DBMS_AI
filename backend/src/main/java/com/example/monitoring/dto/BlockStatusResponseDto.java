package com.example.monitoring.dto;

import com.example.monitoring.domain.BlockedReason;
import com.example.monitoring.domain.TargetDbStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 프로젝트 차단 상태 응답 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockStatusResponseDto {

    private Long databaseConfigId;
    private String databaseName;
    private TargetDbStatus status;

    /** 수집 활성 여부 */
    private Boolean enabled;

    /** 현재 활성 차단 정보 (차단 중이 아니면 null) */
    private ActiveBlockInfo activeBlock;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ActiveBlockInfo {
        private Long blockRecordId;
        private String incidentId;
        private String severity;
        private BlockedReason.BlockType blockType;
        private String reason;
        private String blockedBy;
        private LocalDateTime blockedAt;
    }
}
