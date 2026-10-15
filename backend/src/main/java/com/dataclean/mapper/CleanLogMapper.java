package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.CleanLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 清洗日志数据访问层
 */
@Mapper
public interface CleanLogMapper extends BaseMapper<CleanLog> {

    /**
     * 根据数据集ID查询清洗日志
     */
    @Select("SELECT * FROM clean_log WHERE dataset_id = #{datasetId} ORDER BY created_at DESC")
    List<CleanLog> selectByDatasetId(@Param("datasetId") Long datasetId);
}
