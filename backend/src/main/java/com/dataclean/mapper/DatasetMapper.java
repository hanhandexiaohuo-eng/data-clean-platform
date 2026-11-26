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
     * 带参数绑定的动态查询。
     *
     * <p>调用方必须保证 sql 中的标识符已通过白名单校验，值统一以 #{params[n]} 占位，
     * 由 MyBatis 预编译绑定，不拼接字符串。
     */
    @Select("<script>${sql}</script>")
    List<Map<String, Object>> selectDynamicSqlWithParams(
            @Param("sql") String sql,
            @Param("params") List<Object> params);

    /**
     * 带参数绑定的动态写操作（UPDATE/DELETE/DDL）。
     */
    @Update("<script>${sql}</script>")
    void executeDynamicSqlWithParams(
            @Param("sql") String sql,
            @Param("params") List<Object> params);

    /**
     * 按偏移量取单个值，用于分位数计算，避免整列读入内存。
     */
    @Select("SELECT ${column} AS val FROM ${tableName} WHERE ${column} IS NOT NULL " +
            "ORDER BY ${column} LIMIT 1 OFFSET #{offset}")
    Map<String, Object> selectColumnValueAtOffset(
            @Param("tableName") String tableName,
            @Param("column") String column,
            @Param("offset") int offset);

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

    /** 匿名会话数据集数量（公开模式配额） */
    @Select("SELECT COUNT(*) FROM dataset WHERE owner_type = 'anon' AND anon_session_id = #{sessionId}")
    long countAnonDatasets(@Param("sessionId") Long sessionId);

    /** 匿名会话总存储字节数（公开模式配额） */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM dataset WHERE owner_type = 'anon' AND anon_session_id = #{sessionId}")
    long sumAnonStorage(@Param("sessionId") Long sessionId);

    /** 动态业务表总数（容量指标，不含元数据表） */
    @Select("SELECT COUNT(*) FROM information_schema.tables " +
            "WHERE table_schema = DATABASE() AND table_name LIKE 'data!_%' ESCAPE '!'")
    long countDynamicTables();

    /**
     * 疑似孤儿动态表数量：仅统计上报表，不用于删除。
     *
     * <p>动态表实际命名为 {@code data_<名称>_<时间戳>}，备份表为
     * {@code <原表名>_backup_<时间戳>}；这里只做“无元数据引用”的计数，
     * 任何清理动作都必须由 dataset / clean_log 的精确表名驱动。
     */
    @Select("SELECT COUNT(*) FROM information_schema.tables t " +
            "WHERE t.table_schema = DATABASE() " +
            "AND (t.table_name LIKE 'data!_%' ESCAPE '!' OR t.table_name LIKE '%!_backup!_%' ESCAPE '!') " +
            "AND NOT EXISTS (SELECT 1 FROM dataset d WHERE d.table_name = t.table_name) " +
            "AND NOT EXISTS (SELECT 1 FROM dataset_version v WHERE v.table_name = t.table_name) " +
            "AND NOT EXISTS (SELECT 1 FROM backup_table b WHERE b.table_name = t.table_name) " +
            "AND NOT EXISTS (SELECT 1 FROM clean_log l WHERE l.backup_table = t.table_name)")
    long countOrphanTables();

    /** 动态表实际列名（用于校验历史版本统计字段是否仍然存在）。 */
    @Select("SELECT column_name FROM information_schema.columns " +
            "WHERE table_schema = DATABASE() AND table_name = #{tableName} " +
            "ORDER BY ordinal_position")
    java.util.List<String> selectTableColumns(@Param("tableName") String tableName);
}
