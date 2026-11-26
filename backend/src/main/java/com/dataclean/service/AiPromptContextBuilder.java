package com.dataclean.service;

import com.dataclean.dto.DatasetPreviewVO;
import com.dataclean.util.AiPiiMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI Prompt 数据最小化上下文构建。
 *
 * <ul>
 *   <li>私有：字段名/类型/空值率/唯一值/min/max/avg/std，最多 3 行脱敏样本（每行最多 20 列）；</li>
 *   <li>公开：仅字段名、类型与统计摘要，不发送样本行；</li>
 *   <li>敏感列样本整列替换为 {@code [redacted]}；</li>
 *   <li>所有文本最终再经 {@link AiPiiMasker} 脱敏。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPromptContextBuilder {

    private static final int SAMPLE_ROWS = 3;
    private static final int MAX_SAMPLE_COLUMNS = 20;
    private static final List<String> SENSITIVE_KEYWORDS = List.of(
            "phone", "mobile", "tel", "email", "mail", "idcard", "id_card", "card", "bank",
            "address", "addr", "account", "token", "password", "passwd", "secret", "cookie",
            "身份证", "手机", "电话", "邮箱", "地址", "账号", "卡号", "密码", "令牌");

    private final DatasetService datasetService;
    private final ObjectMapper objectMapper;
    private final AiPiiMasker aiPiiMasker;

    /**
     * 构建可安全放入 Prompt 的数据集上下文 JSON。
     */
    public String build(Long datasetId, boolean publicMode) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (datasetId == null) {
            return "{}";
        }
        try {
            Map<String, Object> stats = datasetService.getDatasetStatistics(datasetId);
            context.put("rowCount", stats.get("rowCount"));
            context.put("columnCount", stats.get("columnCount"));
            context.put("approximate", stats.get("approximate"));
            List<Map<String, Object>> columns = new ArrayList<>();
            Object rawColumns = stats.get("columns");
            if (rawColumns instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> column) {
                        columns.add(reduceColumn(column));
                    }
                }
            }
            context.put("columns", columns);
            if (!publicMode) {
                context.put("sampleRows", sampleRows(datasetId));
            }
        } catch (Exception e) {
            log.warn("构建 AI 上下文失败: datasetId={}, error={}", datasetId, e.getClass().getSimpleName());
            context.put("columns", List.of());
        }
        try {
            return aiPiiMasker.maskText(objectMapper.writeValueAsString(context));
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> reduceColumn(Map<?, ?> column) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("columnName", column.get("columnName"));
        item.put("dataType", column.get("dataType"));
        item.put("nullRate", column.get("nullRate"));
        item.put("uniqueCount", column.get("uniqueCount"));
        item.put("min", column.get("min"));
        item.put("max", column.get("max"));
        item.put("avg", column.get("avg"));
        item.put("std", column.get("std"));
        return item;
    }

    private List<Map<String, Object>> sampleRows(Long datasetId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            DatasetPreviewVO preview = datasetService.previewData(datasetId, 1, SAMPLE_ROWS);
            List<DatasetPreviewVO.ColumnInfoVO> columns = preview.getColumns();
            List<Map<String, Object>> rows = preview.getRows();
            if (columns == null || rows == null) {
                return result;
            }
            int limit = Math.min(columns.size(), MAX_SAMPLE_COLUMNS);
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (int i = 0; i < limit; i++) {
                    String name = columns.get(i).getName();
                    if (isSensitive(name)) {
                        item.put(name, "[redacted]");
                    } else {
                        Object value = row.get(name);
                        item.put(name, value == null ? null : maskValue(String.valueOf(value)));
                    }
                }
                result.add(item);
            }
        } catch (Exception e) {
            log.debug("读取 AI 样本行失败: datasetId={}", datasetId);
        }
        return result;
    }

    private boolean isSensitive(String columnName) {
        if (columnName == null) {
            return false;
        }
        String lower = columnName.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String maskValue(String value) {
        String masked = aiPiiMasker.maskText(value);
        return masked.length() > 120 ? masked.substring(0, 120) : masked;
    }
}
