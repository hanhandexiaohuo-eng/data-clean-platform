package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.DatasetColumn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据集列信息数据访问层
 */
@Mapper
public interface DatasetColumnMapper extends BaseMapper<DatasetColumn> {

    /**
     * 根据数据集ID查询列信息
     */
    @Select("SELECT * FROM dataset_column WHERE dataset_id = #{datasetId} ORDER BY sort_order")
    List<DatasetColumn> selectByDatasetId(@Param("datasetId") Long datasetId);

    /**
     * 获取数值类型的列
     */
    @Select("SELECT * FROM dataset_column WHERE dataset_id = #{datasetId} " +
            "AND data_type IN ('INT', 'BIGINT', 'DECIMAL', 'DOUBLE', 'FLOAT') " +
            "ORDER BY sort_order")
    List<DatasetColumn> selectNumericColumns(@Param("datasetId") Long datasetId);
}
