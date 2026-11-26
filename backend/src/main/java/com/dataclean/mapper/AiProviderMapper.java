package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiProviderMapper extends BaseMapper<AiProvider> {

    @Select("SELECT * FROM ai_provider WHERE name=#{name} LIMIT 1")
    AiProvider selectByName(@Param("name") String name);

    @Select("SELECT COUNT(*) FROM ai_model WHERE provider_id=#{providerId}")
    long countModels(@Param("providerId") Long providerId);
}
