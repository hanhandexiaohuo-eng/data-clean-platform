package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.CleanupLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CleanupLogMapper extends BaseMapper<CleanupLog> {
}
