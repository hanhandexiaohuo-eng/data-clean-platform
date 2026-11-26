package com.dataclean.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 熔断状态视图。
 */
@Data
public class AiCircuitVO {

    private Long id;
    private String circuitKey;
    private String state;
    private Integer failureCount;
    private LocalDateTime openedAt;
    private LocalDateTime cooldownUntil;
    private String lastErrorType;
    private LocalDateTime updatedAt;
}
