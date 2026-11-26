package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiModelMapper extends BaseMapper<AiModel> {

    @Select("SELECT * FROM ai_model WHERE status=1 ORDER BY id ASC")
    List<AiModel> listEnabled();

    @Select("SELECT COUNT(*) FROM ai_scene_route WHERE primary_model_id=#{modelId} OR fallback_model_id=#{modelId}")
    long countRoutes(@Param("modelId") Long modelId);
}
