package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.CleanupItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CleanupItemMapper extends BaseMapper<CleanupItem> {

    @Insert("INSERT IGNORE INTO cleanup_item(anon_session_id, resource_type, resource_key, state, updated_at) " +
            "VALUES(#{sessionId}, #{resourceType}, #{resourceKey}, 'pending', NOW())")
    int insertIgnore(@Param("sessionId") Long sessionId,
                     @Param("resourceType") String resourceType,
                     @Param("resourceKey") String resourceKey);

    @Select("SELECT * FROM cleanup_item WHERE anon_session_id = #{sessionId} ORDER BY id")
    List<CleanupItem> selectBySession(@Param("sessionId") Long sessionId);

    @Update("UPDATE cleanup_item SET state = #{state}, error_type = #{errorType}, updated_at = NOW() WHERE id = #{id}")
    int updateState(@Param("id") Long id,
                    @Param("state") String state,
                    @Param("errorType") String errorType);

    @Delete("DELETE FROM cleanup_item WHERE anon_session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") Long sessionId);
}
