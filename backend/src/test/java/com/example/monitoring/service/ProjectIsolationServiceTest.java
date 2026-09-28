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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectIsolationService 단위 테스트")
class ProjectIsolationServiceTest {

    @Mock
    private DatabaseConfigRepository databaseConfigRepository;

    @Mock
    private BlockedReasonRepository blockedReasonRepository;

    @InjectMocks
    private ProjectIsolationService projectIsolationService;

    private DatabaseConfig activeConfig;
    private DatabaseConfig blockedConfig;
    private IncidentCreatedEvent fatalIncident;

    @BeforeEach
    void setUp() {
        activeConfig = DatabaseConfig.builder()
                .id(1L)
                .name("test-db")
                .host("localhost")
                .port(3306)
                .username("user")
                .password("pass")
                .status(TargetDbStatus.UP)
                .enabled(true)
                .collectionIntervalSeconds(5)
                .build();

        blockedConfig = DatabaseConfig.builder()
                .id(2L)
                .name("blocked-db")
                .host("localhost")
                .port(3307)
                .username("user")
                .password("pass")
                .status(TargetDbStatus.BLOCKED)
                .enabled(false)
                .collectionIntervalSeconds(5)
                .build();

        fatalIncident = IncidentCreatedEvent.builder()
                .incidentId("incident-uuid-001")
                .databaseConfigId(1L)
                .databaseName("test-db")
                .severity(RiskSeverity.FATAL)
                .ruleType("CONNECTION_FAILURE")
                .message("Database connection failed")
                .timestamp(LocalDateTime.now())
                .build();
    }

    // ----------------------------------------------------------------
    // 자동 차단
    // ----------------------------------------------------------------

    @Test
    @DisplayName("FATAL 인시던트 발생 시 DB가 자동 차단된다")
    void autoBlock_whenFatalIncident_shouldBlockDb() {
        // given
        when(databaseConfigRepository.findById(1L)).thenReturn(Optional.of(activeConfig));
        when(databaseConfigRepository.save(any())).thenReturn(activeConfig);

        // when
        projectIsolationService.autoBlockOnFatalIncident(fatalIncident);

        // then: enabled=false, status=BLOCKED 로 저장
        ArgumentCaptor<DatabaseConfig> configCaptor = ArgumentCaptor.forClass(DatabaseConfig.class);
        verify(databaseConfigRepository).save(configCaptor.capture());
        assertThat(configCaptor.getValue().getEnabled()).isFalse();
        assertThat(configCaptor.getValue().getStatus()).isEqualTo(TargetDbStatus.BLOCKED);

        // then: BlockedReason 저장
        ArgumentCaptor<BlockedReason> reasonCaptor = ArgumentCaptor.forClass(BlockedReason.class);
        verify(blockedReasonRepository).save(reasonCaptor.capture());
        BlockedReason saved = reasonCaptor.getValue();
        assertThat(saved.getBlockType()).isEqualTo(BlockedReason.BlockType.AUTO);
        assertThat(saved.getBlockedBy()).isEqualTo("SYSTEM");
        assertThat(saved.getIncidentId()).isEqualTo("incident-uuid-001");
        assertThat(saved.getSeverity()).isEqualTo(RiskSeverity.FATAL);
    }

    @Test
    @DisplayName("이미 BLOCKED 상태인 DB에 자동 차단 호출 시 중복 처리하지 않는다")
    void autoBlock_whenAlreadyBlocked_shouldSkip() {
        // given
        when(databaseConfigRepository.findById(2L)).thenReturn(Optional.of(blockedConfig));
        IncidentCreatedEvent incident = IncidentCreatedEvent.builder()
                .incidentId("inc-002")
                .databaseConfigId(2L)
                .severity(RiskSeverity.FATAL)
                .ruleType("CONNECTION_FAILURE")
                .message("Already blocked")
                .timestamp(LocalDateTime.now())
                .build();

        // when
        projectIsolationService.autoBlockOnFatalIncident(incident);

        // then: 저장 호출 없음
        verify(databaseConfigRepository, never()).save(any());
        verify(blockedReasonRepository, never()).save(any());
    }

    @Test
    @DisplayName("FATAL이 아닌 인시던트로 autoBlock 호출 시 처리하지 않는다")
    void autoBlock_whenNonFatal_shouldSkip() {
        // given
        IncidentCreatedEvent criticalIncident = IncidentCreatedEvent.builder()
                .incidentId("inc-critical")
                .databaseConfigId(1L)
                .severity(RiskSeverity.CRITICAL)
                .ruleType("CPU_USAGE_HIGH")
                .message("CPU 90%")
                .timestamp(LocalDateTime.now())
                .build();

        // when
        projectIsolationService.autoBlockOnFatalIncident(criticalIncident);

        // then
        verify(databaseConfigRepository, never()).findById(any());
        verify(blockedReasonRepository, never()).save(any());
    }

    // ----------------------------------------------------------------
    // 수동 차단
    // ----------------------------------------------------------------

    @Test
    @DisplayName("수동 차단 시 enabled=false, status=BLOCKED, BlockedReason 저장")
    void manualBlock_shouldBlockAndSaveReason() {
        // given
        when(databaseConfigRepository.findById(1L)).thenReturn(Optional.of(activeConfig));
        when(databaseConfigRepository.save(any())).thenReturn(activeConfig);
        BlockedReason savedReason = BlockedReason.builder()
                .id(10L).databaseConfigId(1L)
                .severity(RiskSeverity.INFO)
                .blockType(BlockedReason.BlockType.MANUAL)
                .reason("[MANUAL] 점검 차단")
                .blockedBy("admin")
                .blockedAt(LocalDateTime.now())
                .build();
        when(blockedReasonRepository.save(any())).thenReturn(savedReason);

        // when
        BlockStatusResponseDto result =
                projectIsolationService.manualBlock(1L, "점검 차단", "admin");

        // then
        assertThat(result.getStatus()).isEqualTo(TargetDbStatus.BLOCKED);
        assertThat(result.getEnabled()).isFalse();
        assertThat(result.getActiveBlock()).isNotNull();
        assertThat(result.getActiveBlock().getBlockType()).isEqualTo(BlockedReason.BlockType.MANUAL);
    }

    @Test
    @DisplayName("이미 BLOCKED 상태에서 수동 차단 시 IllegalStateException")
    void manualBlock_whenAlreadyBlocked_shouldThrow() {
        // given
        when(databaseConfigRepository.findById(2L)).thenReturn(Optional.of(blockedConfig));

        // when / then
        assertThatThrownBy(() -> projectIsolationService.manualBlock(2L, "reason", "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already BLOCKED");
    }

    // ----------------------------------------------------------------
    // 수동 해제
    // ----------------------------------------------------------------

    @Test
    @DisplayName("수동 해제 시 enabled=true, status=UNKNOWN, BlockedReason 해제 시각 기록")
    void unblock_shouldReactivateAndRecordUnblock() {
        // given
        when(databaseConfigRepository.findById(2L)).thenReturn(Optional.of(blockedConfig));
        when(databaseConfigRepository.save(any())).thenReturn(blockedConfig);
        BlockedReason activeBlock = BlockedReason.builder()
                .id(5L).databaseConfigId(2L)
                .severity(RiskSeverity.FATAL)
                .blockType(BlockedReason.BlockType.AUTO)
                .reason("[AUTO] test")
                .blockedBy("SYSTEM")
                .blockedAt(LocalDateTime.now().minusMinutes(10))
                .build();
        when(blockedReasonRepository.findByDatabaseConfigIdAndUnblockedAtIsNull(2L))
                .thenReturn(Optional.of(activeBlock));
        when(blockedReasonRepository.save(any())).thenReturn(activeBlock);

        // when
        BlockStatusResponseDto result = projectIsolationService.unblock(2L, "ops-team");

        // then
        ArgumentCaptor<DatabaseConfig> configCaptor = ArgumentCaptor.forClass(DatabaseConfig.class);
        verify(databaseConfigRepository).save(configCaptor.capture());
        assertThat(configCaptor.getValue().getEnabled()).isTrue();
        assertThat(configCaptor.getValue().getStatus()).isEqualTo(TargetDbStatus.UNKNOWN);

        ArgumentCaptor<BlockedReason> reasonCaptor = ArgumentCaptor.forClass(BlockedReason.class);
        verify(blockedReasonRepository).save(reasonCaptor.capture());
        assertThat(reasonCaptor.getValue().getUnblockedBy()).isEqualTo("ops-team");
        assertThat(reasonCaptor.getValue().getUnblockedAt()).isNotNull();
    }

    @Test
    @DisplayName("BLOCKED 상태가 아닌 DB 해제 시 IllegalStateException")
    void unblock_whenNotBlocked_shouldThrow() {
        // given
        when(databaseConfigRepository.findById(1L)).thenReturn(Optional.of(activeConfig));

        // when / then
        assertThatThrownBy(() -> projectIsolationService.unblock(1L, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not currently BLOCKED");
    }

    // ----------------------------------------------------------------
    // 조회
    // ----------------------------------------------------------------

    @Test
    @DisplayName("존재하지 않는 ID 조회 시 EntityNotFoundException")
    void getBlockStatus_whenNotFound_shouldThrow() {
        // given
        when(databaseConfigRepository.findById(999L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> projectIsolationService.getBlockStatus(999L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("getAllBlockedProjects는 BLOCKED 상태 DB 목록을 반환한다")
    void getAllBlockedProjects_shouldReturnBlockedList() {
        // given
        when(databaseConfigRepository.findByStatus(TargetDbStatus.BLOCKED))
                .thenReturn(List.of(blockedConfig));
        when(blockedReasonRepository.findByDatabaseConfigIdAndUnblockedAtIsNull(2L))
                .thenReturn(Optional.empty());

        // when
        List<BlockStatusResponseDto> result = projectIsolationService.getAllBlockedProjects();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDatabaseConfigId()).isEqualTo(2L);
        assertThat(result.get(0).getStatus()).isEqualTo(TargetDbStatus.BLOCKED);
    }
}
