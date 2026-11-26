package com.dataclean.job;

/** 异步任务状态常量（与 async_job.status 一致）。 */
public final class JobStatus {

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String RECOVERABLE = "RECOVERABLE";

    private JobStatus() {
    }

    public static boolean isTerminal(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }
}
