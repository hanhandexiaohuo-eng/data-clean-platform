package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cleanup_item")
public class CleanupItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long anonSessionId;
    private String resourceType;
    private String resourceKey;
    private String state;
    private String errorType;
    private LocalDateTime updatedAt;
}
