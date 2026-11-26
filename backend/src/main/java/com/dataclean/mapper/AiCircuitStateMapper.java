package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiCircuitState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiCircuitStateMapper extends BaseMapper<AiCircuitState> {

    @Select("SELECT * FROM ai_circuit_state WHERE circuit_key=#{key} LIMIT 1")
    AiCircuitState selectByKey(@Param("key") String key);
}
