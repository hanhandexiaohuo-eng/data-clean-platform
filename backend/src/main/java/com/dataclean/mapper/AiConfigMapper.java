package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * AI 配置数据访问层
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfig> {

    /**
     * 获取默认配置
     */
    @Select("SELECT * FROM ai_config WHERE is_default = 1 AND status = 1 LIMIT 1")
    AiConfig selectDefault();

    /**
     * 重置所有默认配置
     */
    @Update("UPDATE ai_config SET is_default = 0 WHERE id != #{id}")
    void resetDefault(@Param("id") Long id);
}
