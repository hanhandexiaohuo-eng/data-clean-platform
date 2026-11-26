package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiQuota;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiQuotaMapper extends BaseMapper<AiQuota> {

    /**
     * 取最具体的一条启用配额：精确场景/模型优先于通配。
     */
    @Select("SELECT * FROM ai_quota WHERE scope_type=#{scopeType} AND enabled=1 " +
            "AND (scene=#{scene} OR scene='*') AND (model_code=#{modelCode} OR model_code='*') " +
            "AND period=#{period} " +
            "ORDER BY (scene='*') ASC, (model_code='*') ASC, id ASC LIMIT 1")
    AiQuota findApplicable(@Param("scopeType") String scopeType,
                           @Param("scene") String scene,
                           @Param("modelCode") String modelCode,
                           @Param("period") String period);
}
