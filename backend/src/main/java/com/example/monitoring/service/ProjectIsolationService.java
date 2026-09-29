package com.example.monitoring.service;

import com.example.monitoring.domain.BlockedReason;
import com.example.monitoring.domain.DatabaseConfig;
import com.example.monitoring.domain.RiskSeverity;
import com.example.monitoring.domain.TargetDbStatus;
import com.example.monitoring.dto.BlockStatusResponseDto;
import com.example.monitoring.dto.IncidentCreatedEvent;
import com.example.monitoring.repository.BlockedReasonRepository;
import com.example.monitoring.repository.DatabaseConfigRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 프로젝트 격리(Block/Unblock) 핵심 서비스.
 *
 * <ul>
 *   <li>자동 차단: FATAL 인시던트 감지 시 {@link #autoBlockOnFatalIncident} 호출</li>
 *   <li>수동 차단: {@link #manualBlock}</li>
 *   <li>수동 해제: {@link #unblock}</li>
 *   <li>상태 조회: {@link #getBlockStatus}, {@link #getAllBlockedProjects}</li>
 *   <li>토글: {@link #toggle}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectIsolationService {

    private final DatabaseConfigRepository databaseConfigRepository;
    private final BlockedReasonRepository blockedReasonRepository;

    // ----------------------------------------------------------------
    // 자동 차단 (FATAL 인시던트 트리거)
    // ----------------------------------------------------------------

    /**
     * FATAL 인시던트 발생 시 해당 DB를 자동으로 차단합니다.
     * 이미 차단 중이면 중복 처리하지 않습니다.
     *
     * @param incident FATAL 인시던트 이벤트
     */
    @Transactional
    public void autoBlockOnFatalIncident(IncidentCreatedEvent incident) {
        if (incident.getSeverity() != RiskSeverity.FATAL) {
            log.warn("[Isolation] autoBlockOnFatalIncident called with non-FATAL severity: {}. Skipping.",
                    incident.getSeverity());
            return;
        }

        Long dbConfigId = incident.getDatabaseConfigId();
        DatabaseConfig config = findConfigOrThrow(dbConfigId);

        // 이미 차단 중이면 중복 차단 방지
        if (config.getStatus() == TargetDbStatus.BLOCKED) {
            log.info("[Isolation] DB [id={}] is already BLOCKED. Skipping auto-block for incidentId={}.",
                    dbConfigId, incident.getIncidentId());
            return;
        }

        // 수집 비활성화 + 상태 BLOCKED
        config.setEnabled(false);
        config.setStatus(TargetDbStatus.BLOCKED);
        databaseConfigRepository.save(config);

        // 차단 이력 저장
        BlockedReason record = BlockedReason.builder()
                .databaseConfigId(dbConfigId)
                .incidentId(incident.getIncidentId())
                .severity(incident.getSeverity())
                .blockType(BlockedReason.BlockType.AUTO)
                .reason(String.format("[AUTO] %s — %s", incident.getRuleType(), incident.getMessage()))
                .blockedBy("SYSTEM")
                .blockedAt(LocalDateTime.now())
                .build();
        blockedReasonRepository.save(record);

        log.warn("[Isolation] DB [id={}, name={}] has been AUTO-BLOCKED due to FATAL incident [id={}].",
                dbConfigId, config.getName(), incident.getIncidentId());
    }

    // ----------------------------------------------------------------
    // 수동 차단
    // ----------------------------------------------------------------

    /**
     * 관리자가 수동으로 특정 DB를 차단합니다.
     *
     * @param dbConfigId 차단 대상 DatabaseConfig ID
     * @param reason     차단 사유
     * @param approvedBy 요청 담당자
     * @throws EntityNotFoundException   DB 설정을 찾을 수 없는 경우
     * @throws IllegalStateException     이미 차단 중인 경우
     */
    @Transactional
    public BlockStatusResponseDto manualBlock(Long dbConfigId, String reason, String approvedBy) {
        DatabaseConfig config = findConfigOrThrow(dbConfigId);

        if (config.getStatus() == TargetDbStatus.BLOCKED) {
            throw new IllegalStateException(
                    String.format("DB [id=%d] is already BLOCKED.", dbConfigId));
        }

        config.setEnabled(false);
        config.setStatus(TargetDbStatus.BLOCKED);
        databaseConfigRepository.save(config);

        BlockedReason record = BlockedReason.builder()
                .databaseConfigId(dbConfigId)
                .severity(RiskSeverity.INFO) // 수동 차단은 심각도 INFO
                .blockType(BlockedReason.BlockType.MANUAL)
                .reason("[MANUAL] " + reason)
                .blockedBy(approvedBy)
                .blockedAt(LocalDateTime.now())
                .build();
        blockedReasonRepository.save(record);

        log.info("[Isolation] DB [id={}, name={}] has been MANUALLY BLOCKED by '{}'.",
                dbConfigId, config.getName(), approvedBy);

        return buildBlockStatusResponse(config, record);
    }

    // ----------------------------------------------------------------
    // 수동 해제 + 승인
    // ----------------------------------------------------------------

    /**
     * 차단된 DB를 해제하고 수집을 재개합니다.
     *
     * @param dbConfigId   해제 대상 DatabaseConfig ID
     * @param approvedBy   승인자
     * @throws EntityNotFoundException   DB 설정을 찾을 수 없는 경우
     * @throws IllegalStateException     현재 차단 상태가 아닌 경우
     */
    @Transactional
    public BlockStatusResponseDto unblock(Long dbConfigId, String approvedBy) {
        DatabaseConfig config = findConfigOrThrow(dbConfigId);

        if (config.getStatus() != TargetDbStatus.BLOCKED) {
            throw new IllegalStateException(
                    String.format("DB [id=%d] is not currently BLOCKED (status=%s).",
                            dbConfigId, config.getStatus()));
        }

        // 활성 차단 레코드 해제 처리
        Optional<BlockedReason> activeBlock =
                blockedReasonRepository.findByDatabaseConfigIdAndUnblockedAtIsNull(dbConfigId);

        activeBlock.ifPresent(block -> {
            block.setUnblockedAt(LocalDateTime.now());
            block.setUnblockedBy(approvedBy);
            blockedReasonRepository.save(block);
        });

        // 수집 재활성화 + 상태 UNKNOWN (다음 수집 사이클에서 UP/DOWN 결정)
        config.setEnabled(true);
        config.setStatus(TargetDbStatus.UNKNOWN);
        databaseConfigRepository.save(config);

        log.info("[Isolation] DB [id={}, name={}] has been UNBLOCKED by '{}'.",
                dbConfigId, config.getName(), approvedBy);

        return buildBlockStatusResponse(config, null);
    }

    // ----------------------------------------------------------------
    // 토글 (차단 ↔ 해제)
    // ----------------------------------------------------------------

    /**
     * 현재 차단 상태이면 해제, 아니면 차단합니다.
     *
     * @param dbConfigId 대상 ID
     * @param reason     사유 (차단 시에만 사용)
     * @param approvedBy 요청 담당자
     */
    @Transactional
    public BlockStatusResponseDto toggle(Long dbConfigId, String reason, String approvedBy) {
        DatabaseConfig config = findConfigOrThrow(dbConfigId);

        if (config.getStatus() == TargetDbStatus.BLOCKED) {
            return unblock(dbConfigId, approvedBy);
        } else {
            return manualBlock(dbConfigId, reason, approvedBy);
        }
    }

    // ----------------------------------------------------------------
    // 조회
    // ----------------------------------------------------------------

    /**
     * 특정 DB의 현재 차단 상태를 조회합니다.
     */
    @Transactional(readOnly = true)
    public BlockStatusResponseDto getBlockStatus(Long dbConfigId) {
        DatabaseConfig config = findConfigOrThrow(dbConfigId);

        Optional<BlockedReason> activeBlock =
                blockedReasonRepository.findByDatabaseConfigIdAndUnblockedAtIsNull(dbConfigId);

        return buildBlockStatusResponse(config, activeBlock.orElse(null));
    }

    /**
     * 현재 차단된 모든 DB 목록을 반환합니다.
     */
    @Transactional(readOnly = true)
    public List<BlockStatusResponseDto> getAllBlockedProjects() {
        return databaseConfigRepository.findByStatus(TargetDbStatus.BLOCKED).stream()
                .map(config -> {
                    Optional<BlockedReason> activeBlock =
                            blockedReasonRepository.findByDatabaseConfigIdAndUnblockedAtIsNull(config.getId());
                    return buildBlockStatusResponse(config, activeBlock.orElse(null));
                })
                .collect(Collectors.toList());
    }

    // ----------------------------------------------------------------
    // 내부 유틸
    // ----------------------------------------------------------------

    private DatabaseConfig findConfigOrThrow(Long dbConfigId) {
        return databaseConfigRepository.findById(dbConfigId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "DatabaseConfig not found: id=" + dbConfigId));
    }

    private BlockStatusResponseDto buildBlockStatusResponse(DatabaseConfig config,
                                                             BlockedReason activeBlock) {
        BlockStatusResponseDto.ActiveBlockInfo blockInfo = null;
        if (activeBlock != null) {
            blockInfo = BlockStatusResponseDto.ActiveBlockInfo.builder()
                    .blockRecordId(activeBlock.getId())
                    .incidentId(activeBlock.getIncidentId())
                    .severity(activeBlock.getSeverity() != null ? activeBlock.getSeverity().name() : null)
                    .blockType(activeBlock.getBlockType())
                    .reason(activeBlock.getReason())
                    .blockedBy(activeBlock.getBlockedBy())
                    .blockedAt(activeBlock.getBlockedAt())
                    .build();
        }

        return BlockStatusResponseDto.builder()
                .databaseConfigId(config.getId())
                .databaseName(config.getName())
                .status(config.getStatus())
                .enabled(config.getEnabled())
                .activeBlock(blockInfo)
                .build();
    }
}
