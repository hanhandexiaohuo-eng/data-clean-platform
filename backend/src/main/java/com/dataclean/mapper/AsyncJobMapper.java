package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AsyncJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AsyncJobMapper extends BaseMapper<AsyncJob> {

    /** 原子抢占一个待执行任务（多实例/多线程安全）。 */
    @Update("UPDATE async_job SET status='RUNNING', started_at=NOW(), heartbeat_at=NOW(), " +
            "phase='STARTING', progress=0 WHERE id=#{id} AND status='PENDING'")
    int markRunning(@Param("id") Long id);

    @Update("UPDATE async_job SET heartbeat_at=NOW(), progress=#{progress}, phase=#{phase}, " +
            "processed_rows=#{processedRows}, total_rows=#{totalRows} WHERE id=#{id} AND status IN ('RUNNING','CANCEL_REQUESTED')")
    int updateProgress(@Param("id") Long id, @Param("phase") String phase,
                       @Param("progress") int progress,
                       @Param("processedRows") long processedRows,
                       @Param("totalRows") long totalRows);

    @Update("UPDATE async_job SET status=#{status}, phase=#{phase}, progress=#{progress}, " +
            "processed_rows=#{processedRows}, total_rows=#{totalRows}, output_version_id=#{outputVersionId}, " +
            "error_code=#{errorCode}, error_summary=#{errorSummary}, result_summary=#{resultSummary}, " +
            "finished_at=NOW() WHERE id=#{id} AND status IN ('RUNNING','CANCEL_REQUESTED')")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("phase") String phase,
               @Param("progress") int progress, @Param("processedRows") long processedRows,
               @Param("totalRows") long totalRows, @Param("outputVersionId") Long outputVersionId,
               @Param("errorCode") String errorCode, @Param("errorSummary") String errorSummary,
               @Param("resultSummary") String resultSummary);

    @Update("UPDATE async_job SET status='CANCEL_REQUESTED', cancel_requested_at=NOW() " +
            "WHERE id=#{id} AND status IN ('PENDING','RUNNING')")
    int requestCancel(@Param("id") Long id);

    @Update("UPDATE async_job SET status='CANCELLED', phase='CANCELLED', finished_at=NOW() " +
            "WHERE id=#{id} AND status='PENDING'")
    int cancelPending(@Param("id") Long id);

    @Update("UPDATE async_job SET status=#{to}, retry_count=retry_count+1, error_code=NULL, error_summary=NULL, " +
            "started_at=NULL, finished_at=NULL, progress=0, phase='' WHERE id=#{id} AND status=#{from}")
    int retry(@Param("id") Long id, @Param("from") String from, @Param("to") String to);

    /**
     * 启动时收敛未完成任务：取消中的任务直接结束为 CANCELLED，
     * 其余运行中任务降级为 RECOVERABLE 等待人工重试，避免永久卡在 RUNNING。
     * status 必须放在最后赋值（MySQL 单表 UPDATE 从左到右求值）。
     */
    @Update("UPDATE async_job SET " +
            "phase=IF(status='CANCEL_REQUESTED','CANCELLED','RECOVERABLE'), " +
            "error_code=IF(status='CANCEL_REQUESTED',NULL,'PROCESS_RESTART'), " +
            "error_summary=IF(status='CANCEL_REQUESTED','已取消','服务重启，任务已安全停止，可重试'), " +
            "finished_at=NOW(), " +
            "status=IF(status='CANCEL_REQUESTED','CANCELLED','RECOVERABLE') " +
            "WHERE status IN ('RUNNING','CANCEL_REQUESTED')")
    int recoverRunning();

    @Select("SELECT COUNT(*) FROM async_job WHERE anon_session_id=#{sessionId} " +
            "AND status IN ('PENDING','RUNNING','CANCEL_REQUESTED')")
    long countActiveBySession(@Param("sessionId") Long sessionId);

    @Select("SELECT COUNT(*) FROM async_job WHERE anon_session_id=#{sessionId} AND created_at >= #{since}")
    long countCreatedSince(@Param("sessionId") Long sessionId, @Param("since") LocalDateTime since);

    @Select("SELECT * FROM async_job WHERE status='PENDING' ORDER BY created_at ASC LIMIT #{limit}")
    List<AsyncJob> selectPending(@Param("limit") int limit);
}
