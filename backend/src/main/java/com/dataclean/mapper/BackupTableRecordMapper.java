package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.BackupTableRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BackupTableRecordMapper extends BaseMapper<BackupTableRecord> {

    @Select("SELECT * FROM backup_table WHERE anon_session_id=#{sessionId} AND state='READY'")
    List<BackupTableRecord> listReadyBySession(@Param("sessionId") Long sessionId);

    @Select("SELECT * FROM backup_table WHERE state='READY'")
    List<BackupTableRecord> listReady();
}
