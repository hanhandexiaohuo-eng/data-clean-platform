package com.dataclean.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 清洗结果视图对象
 */
@Data
public class CleanResultVO {

    /**
     * 清洗前行数
     */
    private Integer beforeCount;

    /**
     * 清洗后行数
     */
    private Integer afterCount;

    /**
     * 受影响行数
     */
    private Integer affectedRows;

    /**
     * 执行的规则
     */
    private List<String> executedRules;

    /**
     * 备份表名
     */
    private String backupTable;

    /**
     * 预览数据(预览模式返回)
     */
    private List<Map<String, Object>> previewData;

    /**
     * 执行耗时(毫秒)
     */
    private Integer executeTimeMs;

    /**
     * 异步任务ID（正式执行时返回，前端据此轮询进度）
     */
    private Long jobId;

    /**
     * 新产生的数据版本ID（任务成功后可通过任务详情查询）
     */
    private Long versionId;

    /**
     * 当前可用版本ID
     */
    private Long currentVersionId;
}
