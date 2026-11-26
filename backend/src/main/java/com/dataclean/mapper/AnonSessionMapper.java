package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AnonSession;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AnonSessionMapper extends BaseMapper<AnonSession> {

    @Select("SELECT * FROM anon_session WHERE token_hash = #{tokenHash} AND revoked = 0 " +
            "AND expires_at > #{now} LIMIT 1")
    AnonSession selectActive(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM anon_session WHERE ip_hash = #{ipHash} AND revoked = 0 AND expires_at > #{now}")
    long countActiveByIp(@Param("ipHash") String ipHash, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM anon_session WHERE revoked = 0 AND expires_at > #{now}")
    long countActive(@Param("now") LocalDateTime now);

    /** 到期或已注销且尚未清理的会话 */
    @Select("SELECT * FROM anon_session WHERE cleanup_state IN ('active','failed') " +
            "AND (revoked = 1 OR expires_at < #{cutoff}) ORDER BY id LIMIT #{limit}")
    List<AnonSession> selectPurgeCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 上一轮中断的 purging 会话，超时后可重新认领 */
    @Select("SELECT * FROM anon_session WHERE cleanup_state = 'purging' " +
            "AND (purge_started_at IS NULL OR purge_started_at < #{staleBefore}) ORDER BY id LIMIT #{limit}")
    List<AnonSession> selectStalePurging(@Param("staleBefore") LocalDateTime staleBefore, @Param("limit") int limit);

    @Update("UPDATE anon_session SET cleanup_state = 'purging', purge_started_at = NOW(), " +
            "cleanup_attempts = cleanup_attempts + 1, last_cleanup_error = NULL " +
            "WHERE id = #{id} AND cleanup_state IN ('active','failed','purging')")
    int claimForPurge(@Param("id") Long id);

    @Update("UPDATE anon_session SET cleanup_state = 'purged', purged_at = NOW() WHERE id = #{id}")
    int markPurged(@Param("id") Long id);

    @Update("UPDATE anon_session SET cleanup_state = 'failed', last_cleanup_error = #{error} WHERE id = #{id}")
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /** last_seen_at 节流更新：只有超过阈值才写入 */
    @Update("UPDATE anon_session SET last_seen_at = NOW() " +
            "WHERE id = #{id} AND (last_seen_at IS NULL OR last_seen_at < #{threshold})")
    int touchLastSeen(@Param("id") Long id, @Param("threshold") LocalDateTime threshold);

    @Delete("DELETE FROM anon_session WHERE cleanup_state = 'purged' AND purged_at < #{before} LIMIT #{limit}")
    int deleteTombstones(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
