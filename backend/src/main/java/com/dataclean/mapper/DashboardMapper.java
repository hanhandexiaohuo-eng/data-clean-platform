package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.Dashboard;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DashboardMapper extends BaseMapper<Dashboard> {
}
