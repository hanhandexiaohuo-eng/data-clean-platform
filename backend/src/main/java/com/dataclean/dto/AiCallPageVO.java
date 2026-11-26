package com.dataclean.dto;

import com.dataclean.entity.AiCallLog;
import lombok.Data;

import java.util.List;

/**
 * AI 调用审计分页结果。
 */
@Data
public class AiCallPageVO {

    private long total;
    private List<AiCallLog> items;
}
