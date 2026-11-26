package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.ExportRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExportRecordMapper extends BaseMapper<ExportRecord> {

    @Select("SELECT COUNT(*) FROM export_record WHERE anon_session_id = #{sessionId}")
    long countBySession(@Param("sessionId") Long sessionId);
}
