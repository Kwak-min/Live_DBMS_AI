package com.example.monitoring.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 수동 차단 / 해제 요청 DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualBlockRequestDto {

    /** 차단/해제 사유 */
    @NotBlank(message = "reason must not be blank")
    @Size(max = 500, message = "reason must be 500 characters or less")
    private String reason;

    /** 요청 담당자 (승인자) */
    @NotBlank(message = "approvedBy must not be blank")
    @Size(max = 100, message = "approvedBy must be 100 characters or less")
    private String approvedBy;
}
