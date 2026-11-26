package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("anon_session")
public class AnonSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tokenHash;
    private LocalDateTime createdAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime expiresAt;
    private String ipHash;
    private Integer revoked;
    private String cleanupState;
    private Integer cleanupAttempts;
    private LocalDateTime purgeStartedAt;
    private LocalDateTime purgedAt;
    private String lastCleanupError;
}
