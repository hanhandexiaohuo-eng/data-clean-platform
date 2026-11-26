package com.dataclean.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 异步导入提交结果：前端凭 jobId 轮询任务状态。
 */
@Data
@AllArgsConstructor
public class AsyncImportVO {

    private Long jobId;
    private Long datasetId;
    private boolean async;
}
