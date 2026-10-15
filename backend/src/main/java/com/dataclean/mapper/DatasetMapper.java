package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.Dataset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 数据集数据访问层
 */
@Mapper
public interface DatasetMapper extends BaseMapper<Dataset> {

    /**
     * 查询动态表数据
     */
    @Select("SELECT * FROM ${tableName} LIMIT #{limit}")
    List<Map<String, Object>> selectDynamicData(@Param("tableName") String tableName, @Param("limit") Integer limit);

    /**
     * 查询动态表数据(分页)
     */
    @Select("SELECT * FROM ${tableName} LIMIT #{offset}, #{limit}")
    List<Map<String, Object>> selectDynamicDataPage(
            @Param("tableName") String tableName,
            @Param("offset") Integer offset,
            @Param("limit") Integer limit);

    /**
     * 查询动态表总行数
     */
    @Select("SELECT COUNT(*) FROM ${tableName}")
    Long selectDynamicDataCount(@Param("tableName") String tableName);

    /**
     * 执行动态SQL
     */
    @Update("${sql}")
    void executeDynamicSql(@Param("sql") String sql);

    /**
     * 查询动态SQL返回列表
     */
    @Select("${sql}")
    List<Map<String, Object>> selectDynamicSql(@Param("sql") String sql);

    /**
     * 获取数值列的统计信息
     */
    @Select("SELECT AVG(${column}) as avg_val, STDDEV(${column}) as std_val, " +
            "MIN(${column}) as min_val, MAX(${column}) as max_val, " +
            "COUNT(*) as total_count, COUNT(${column}) as non_null_count " +
            "FROM ${tableName}")
    Map<String, Object> selectColumnStatistics(
            @Param("tableName") String tableName,
            @Param("column") String column);

    /**
     * 获取分位数
     */
    @Select("SELECT ${column} as val FROM ${tableName} WHERE ${column} IS NOT NULL ORDER BY ${column}")
    List<Map<String, Object>> selectColumnValues(
            @Param("tableName") String tableName,
            @Param("column") String column);
}
