package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.SavedChart;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SavedChartMapper extends BaseMapper<SavedChart> {
}
