package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.DataQualityReport;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataQualityReportMapper extends BaseMapper<DataQualityReport> {
}
