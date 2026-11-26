package com.dataclean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataclean.entity.DatasetVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DatasetVersionMapper extends BaseMapper<DatasetVersion> {

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM dataset_version WHERE dataset_id=#{datasetId}")
    int maxVersionNo(@Param("datasetId") Long datasetId);

    @Select("SELECT * FROM dataset_version WHERE dataset_id=#{datasetId} ORDER BY version_no DESC")
    List<DatasetVersion> listByDataset(@Param("datasetId") Long datasetId);

    @Select("SELECT * FROM dataset_version WHERE dataset_id=#{datasetId} AND status='ACTIVE' " +
            "ORDER BY version_no DESC LIMIT 1")
    DatasetVersion selectActive(@Param("datasetId") Long datasetId);

    @Update("UPDATE dataset_version SET status='ARCHIVED' WHERE dataset_id=#{datasetId} AND status='ACTIVE'")
    int archiveActive(@Param("datasetId") Long datasetId);

    @Update("UPDATE dataset_version SET status='ACTIVE', activated_at=NOW() WHERE id=#{id}")
    int activate(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM dataset_version WHERE dataset_id=#{datasetId} AND status NOT IN ('ACTIVE')")
    long countArchived(@Param("datasetId") Long datasetId);
}
