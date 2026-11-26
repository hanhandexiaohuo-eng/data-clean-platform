package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.DashboardItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DashboardItemMapper extends BaseMapper<DashboardItem> {

    @Select("SELECT * FROM dashboard_item WHERE dashboard_id=#{dashboardId} ORDER BY sort_order ASC, id ASC")
    List<DashboardItem> listByDashboard(@Param("dashboardId") Long dashboardId);

    @Select("SELECT COUNT(*) FROM dashboard_item WHERE dashboard_id=#{dashboardId}")
    long countByDashboard(@Param("dashboardId") Long dashboardId);
}
