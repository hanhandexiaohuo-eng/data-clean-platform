package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiGenerationLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 生成历史数据访问层
 */
@Mapper
public interface AiGenerationLogMapper extends BaseMapper<AiGenerationLog> {

    /**
     * 根据数据集ID查询生成历史
     */
    @Select("SELECT * FROM ai_generation_log WHERE dataset_id = #{datasetId} ORDER BY created_at DESC LIMIT #{limit}")
    List<AiGenerationLog> selectByDatasetId(@Param("datasetId") Long datasetId, @Param("limit") Integer limit);

    /**
     * 根据上下文类型查询
     */
    @Select("SELECT * FROM ai_generation_log WHERE dataset_id = #{datasetId} AND context_type = #{contextType} ORDER BY created_at DESC LIMIT 1")
    AiGenerationLog selectLatestByContext(@Param("datasetId") Long datasetId, @Param("contextType") String contextType);
}
