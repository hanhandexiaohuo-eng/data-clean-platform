package com.dataclean.dto;

import lombok.Data;

import java.util.List;

/**
 * 清洗执行请求DTO
 */
@Data
public class CleanExecuteDTO {

    /**
     * 数据集ID
     */
    private Long datasetId;

    /**
     * 要执行的规则ID列表
     */
    private List<Long> ruleIds;

    /**
     * 是否预览模式(不实际执行)
     */
    private Boolean previewMode = false;
}
