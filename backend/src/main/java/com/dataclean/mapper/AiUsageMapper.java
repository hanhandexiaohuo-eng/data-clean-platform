package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface AiUsageMapper extends BaseMapper<AiUsage> {

    @Insert("INSERT INTO ai_usage " +
            "(scope_type, scope_key_hash, scene, model_code, period_key, calls, input_tokens, output_tokens, cost) " +
            "VALUES (#{scopeType}, #{scopeKey}, #{scene}, #{modelCode}, #{periodKey}, #{calls}, #{inputTokens}, #{outputTokens}, #{cost}) " +
            "ON DUPLICATE KEY UPDATE calls = calls + #{calls}, " +
            "input_tokens = input_tokens + #{inputTokens}, " +
            "output_tokens = output_tokens + #{outputTokens}, " +
            "cost = cost + #{cost}, updated_at = NOW()")
    int accumulate(@Param("scopeType") String scopeType,
                   @Param("scopeKey") String scopeKey,
                   @Param("scene") String scene,
                   @Param("modelCode") String modelCode,
                   @Param("periodKey") String periodKey,
                   @Param("calls") long calls,
                   @Param("inputTokens") long inputTokens,
                   @Param("outputTokens") long outputTokens,
                   @Param("cost") BigDecimal cost);

    @Select("SELECT COALESCE(SUM(calls),0) FROM ai_usage WHERE scope_type=#{scopeType} " +
            "AND scope_key_hash=#{scopeKey} AND period_key=#{periodKey} " +
            "AND (scene=#{scene} OR #{scene}='*') AND (model_code=#{modelCode} OR #{modelCode}='*')")
    long sumCalls(@Param("scopeType") String scopeType,
                  @Param("scopeKey") String scopeKey,
                  @Param("scene") String scene,
                  @Param("modelCode") String modelCode,
                  @Param("periodKey") String periodKey);

    @Select("SELECT COALESCE(SUM(input_tokens + output_tokens),0) FROM ai_usage WHERE scope_type=#{scopeType} " +
            "AND scope_key_hash=#{scopeKey} AND period_key=#{periodKey} " +
            "AND (scene=#{scene} OR #{scene}='*') AND (model_code=#{modelCode} OR #{modelCode}='*')")
    long sumTokens(@Param("scopeType") String scopeType,
                   @Param("scopeKey") String scopeKey,
                   @Param("scene") String scene,
                   @Param("modelCode") String modelCode,
                   @Param("periodKey") String periodKey);

    @Select("SELECT COALESCE(SUM(cost),0) FROM ai_usage WHERE scope_type=#{scopeType} " +
            "AND scope_key_hash=#{scopeKey} AND period_key=#{periodKey} " +
            "AND (scene=#{scene} OR #{scene}='*') AND (model_code=#{modelCode} OR #{modelCode}='*')")
    BigDecimal sumCost(@Param("scopeType") String scopeType,
                       @Param("scopeKey") String scopeKey,
                       @Param("scene") String scene,
                       @Param("modelCode") String modelCode,
                       @Param("periodKey") String periodKey);
}
