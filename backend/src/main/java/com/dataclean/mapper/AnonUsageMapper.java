package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AnonUsageDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface AnonUsageMapper extends BaseMapper<AnonUsageDaily> {

    @Insert("INSERT INTO anon_usage_daily(scope_type, scope_key, metric, usage_date, counter) " +
            "VALUES(#{scopeType}, #{scopeKey}, #{metric}, #{usageDate}, #{delta}) " +
            "ON DUPLICATE KEY UPDATE counter = counter + #{delta}")
    int increment(@Param("scopeType") String scopeType,
                  @Param("scopeKey") String scopeKey,
                  @Param("metric") String metric,
                  @Param("usageDate") LocalDate usageDate,
                  @Param("delta") long delta);

    @Select("SELECT counter FROM anon_usage_daily WHERE scope_type = #{scopeType} " +
            "AND scope_key = #{scopeKey} AND metric = #{metric} AND usage_date = #{usageDate} LIMIT 1")
    Long getCounter(@Param("scopeType") String scopeType,
                    @Param("scopeKey") String scopeKey,
                    @Param("metric") String metric,
                    @Param("usageDate") LocalDate usageDate);

    @Select("SELECT COALESCE(SUM(counter), 0) FROM anon_usage_daily " +
            "WHERE scope_type = 'global' AND metric = #{metric} AND usage_date = #{usageDate}")
    long sumGlobal(@Param("metric") String metric, @Param("usageDate") LocalDate usageDate);
}
