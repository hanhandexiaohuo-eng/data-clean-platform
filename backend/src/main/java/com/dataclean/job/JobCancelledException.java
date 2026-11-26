package com.dataclean.job;

import com.dataclean.exception.BusinessException;

/** 任务在安全检查点检测到取消请求时抛出，用于回滚未完成产物。 */
public class JobCancelledException extends BusinessException {

    public JobCancelledException() {
        super(409, "任务已取消");
    }
}
