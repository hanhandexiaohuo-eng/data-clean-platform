package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_challenge")
public class AiChallenge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String challengeHash;
    private Long anonSessionId;
    private String ipHash;
    /** 用途：ai=公开 AI 调用；session=匿名会话引导（此时 anon_session_id 为 NULL） */
    private String purpose;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;
}
