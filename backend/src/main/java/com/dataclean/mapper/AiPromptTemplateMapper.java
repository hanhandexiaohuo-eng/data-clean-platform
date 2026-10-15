package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiPromptTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AI Prompt 模板数据访问层
 */
@Mapper
public interface AiPromptTemplateMapper extends BaseMapper<AiPromptTemplate> {

    /**
     * 根据模板标识查询
     */
    @Select("SELECT * FROM ai_prompt_template WHERE template_key = #{templateKey} AND status = 1 LIMIT 1")
    AiPromptTemplate selectByKey(@Param("templateKey") String templateKey);
}
