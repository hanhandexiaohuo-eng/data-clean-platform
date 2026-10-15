package com.dataclean.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据集预览视图对象
 */
@Data
public class DatasetPreviewVO {

    /**
     * 列信息
     */
    private List<ColumnInfoVO> columns;

    /**
     * 数据行
     */
    private List<Map<String, Object>> rows;

    /**
     * 总行数
     */
    private Long total;

    /**
     * 当前页
     */
    private Integer page;

    /**
     * 每页大小
     */
    private Integer pageSize;

    /**
     * 列信息VO
     */
    @Data
    public static class ColumnInfoVO {
        private String name;
        private String type;
        private String label;
    }
}
