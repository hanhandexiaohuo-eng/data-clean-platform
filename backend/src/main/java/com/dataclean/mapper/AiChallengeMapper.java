package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.AiChallenge;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiChallengeMapper extends BaseMapper<AiChallenge> {

    @Select("SELECT * FROM ai_challenge WHERE challenge_hash = #{hash} LIMIT 1")
    AiChallenge selectByHash(@Param("hash") String hash);

    /** 原子核销：只有未使用过才能成功，影响行数必须为 1 */
    @Update("UPDATE ai_challenge SET used_at = NOW() WHERE id = #{id} AND used_at IS NULL")
    int consume(@Param("id") Long id);

    @Delete("DELETE FROM ai_challenge WHERE expires_at < #{before}")
    int deleteExpired(@Param("before") LocalDateTime before);
}
