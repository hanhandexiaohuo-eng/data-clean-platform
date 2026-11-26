package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_circuit_state")
public class AiCircuitState {

    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_HALF_OPEN = "HALF_OPEN";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String circuitKey;
    private String state;
    private Integer failureCount;
    private LocalDateTime openedAt;
    private LocalDateTime cooldownUntil;
    private String lastErrorType;
    private LocalDateTime updatedAt;
}
