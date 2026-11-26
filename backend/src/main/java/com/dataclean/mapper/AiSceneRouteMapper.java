package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiSceneRoute;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiSceneRouteMapper extends BaseMapper<AiSceneRoute> {

    @Select("SELECT * FROM ai_scene_route WHERE scene=#{scene} LIMIT 1")
    AiSceneRoute selectByScene(@Param("scene") String scene);
}
