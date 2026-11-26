package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.ChartView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChartViewMapper extends BaseMapper<ChartView> {

    @Select("SELECT COUNT(*) FROM chart_view WHERE anon_session_id = #{sessionId}")
    long countBySession(@Param("sessionId") Long sessionId);
}
