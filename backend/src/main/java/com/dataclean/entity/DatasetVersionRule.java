package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本规则快照：清洗规则可能在之后被修改或删除，版本必须保留当时的完整配置。
 */
@Data
@TableName("dataset_version_rule")
public class DatasetVersionRule {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;
    private Long ruleId;
    private String ruleType;
    private String ruleConfig;
    private Integer sortOrder;
    private String snapshotHash;
    private LocalDateTime createdAt;
}
