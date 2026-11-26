package com.dataclean.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 配额/预算使用汇总（仅私有模式）。
 */
@Data
public class AiUsageSummaryVO {

    private LocalDate date;
    private List<Item> items;

    @Data
    public static class Item {
        private String scopeType;
        private String scene;
        private String modelCode;
        private long calls;
        private long tokens;
        private BigDecimal cost;
    }
}
