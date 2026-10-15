package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.CleanRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 清洗规则数据访问层
 */
@Mapper
public interface CleanRuleMapper extends BaseMapper<CleanRule> {

    /**
     * 根据数据集ID查询启用的规则，按执行顺序排序
     */
    @Select("SELECT * FROM clean_rule WHERE dataset_id = #{datasetId} AND status = 1 ORDER BY sort_order")
    List<CleanRule> selectActiveRulesByDatasetId(@Param("datasetId") Long datasetId);
}
