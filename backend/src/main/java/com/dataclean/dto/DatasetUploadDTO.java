package com.dataclean.dto;

import lombok.Data;

/**
 * 数据集上传请求DTO
 */
@Data
public class DatasetUploadDTO {

    /**
     * 数据集名称
     */
    private String name;

    /**
     * 数据集描述
     */
    private String description;

    /**
     * 是否包含表头
     */
    private Boolean hasHeader = true;

    /**
     * 编码格式(默认UTF-8)
     */
    private String encoding = "UTF-8";
}
