package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    @Delete("DELETE FROM audit_log WHERE anon_session_id=#{sessionId}")
    int deleteBySession(@Param("sessionId") Long sessionId);
}
