package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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

    /**
     * 查询仍保存明文 API Key 的存量配置（仅迁移使用）。
     */
    @Select("SELECT * FROM ai_config WHERE api_key IS NOT NULL AND api_key != '' " +
            "AND (api_key_cipher IS NULL OR api_key_cipher = '')")
    List<AiConfig> selectLegacyPlaintextConfigs();

    /**
     * 迁移完成后清空明文字段。
     */
    @Update("UPDATE ai_config SET api_key = NULL WHERE id = #{id}")
    void clearPlainApiKey(@Param("id") Long id);

    /**
     * 兜底清理：凡已有密文的行，明文字段一律置空（防止“写密文成功、清明文失败”后残留）。
     */
    @Update("UPDATE ai_config SET api_key = NULL WHERE api_key IS NOT NULL AND api_key != '' " +
            "AND api_key_cipher IS NOT NULL AND api_key_cipher != ''")
    int clearMigratedPlainKeys();
}
