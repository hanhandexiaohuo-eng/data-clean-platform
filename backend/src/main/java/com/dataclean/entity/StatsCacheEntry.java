package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统计结果缓存。以 datasetId + versionId 为失效条件，版本切换后不再命中。
 */
@Data
@TableName("stats_cache")
public class StatsCacheEntry {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Long versionId;
    private String payload;
    private Long rowCount;
    private LocalDateTime computedAt;
    private LocalDateTime expiresAt;
}
