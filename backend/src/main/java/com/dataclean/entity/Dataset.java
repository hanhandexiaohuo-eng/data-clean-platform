package com.dataclean.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("dataset")
public class Dataset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String tableName;
    /** 当前可用版本ID；版本切换只改指针，不覆盖历史版本数据。 */
    private Long currentVersionId;
    private Integer rowCount;
    private Integer columnCount;
    private Integer status;
    /** 归属类型：private（历史/私有）、demo（官方演示）、anon（匿名会话） */
    private String ownerType;
    /** 匿名会话ID；private/demo 为 null。仅服务端使用，不返回前端。 */
    @JsonIgnore
    private Long anonSessionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
