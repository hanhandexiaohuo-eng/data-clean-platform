package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 保存的图表视图。
 *
 * <p>只保存白名单化的图表配置与保存时的数据版本；
 * 数据版本变化时通过状态接口提示刷新，不静默套用旧配置。
 */
@Data
@TableName("saved_chart")
public class SavedChart {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Long datasetVersionId;
    private String ownerType;
    private Long anonSessionId;
    private Long userId;
    private String title;
    private String chartType;
    /** 图表配置 JSON（仅白名单键）。 */
    private String chartConfig;
    private String configHash;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
