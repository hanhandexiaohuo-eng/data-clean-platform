package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.StatsCacheEntry;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StatsCacheMapper extends BaseMapper<StatsCacheEntry> {

    @Delete("DELETE FROM stats_cache WHERE dataset_id=#{datasetId}")
    int deleteByDataset(@Param("datasetId") Long datasetId);
}
