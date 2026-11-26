package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * AI 供应商配置。
 *
 * <p>API Key 只保存密文与掩码；密文禁止任何序列化/日志输出路径。
 */
@Data
@TableName("ai_provider")
public class AiProvider {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String apiUrl;

    @JsonIgnore
    @ToString.Exclude
    private String apiKeyCipher;

    private String apiKeyMask;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
