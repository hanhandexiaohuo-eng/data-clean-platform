package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataclean.dto.DatasetPreviewVO;
import com.dataclean.dto.DatasetUploadDTO;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.util.FileParserUtil;
import com.dataclean.util.FileParserUtil.ParseResult;
import com.dataclean.util.SqlBuilderUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据集业务服务层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DynamicTableService dynamicTableService;

    /**
     * 分页查询数据集列表
     */
    public Page<Dataset> pageList(Integer page, Integer size, String keyword) {
        LambdaQueryWrapper<Dataset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Dataset::getStatus, 1);
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w.like(Dataset::getName, keyword)
                    .or()
                    .like(Dataset::getDescription, keyword));
        }
        wrapper.orderByDesc(Dataset::getCreatedAt);
        return datasetMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 根据ID查询数据集
     */
    public Dataset getById(Long id) {
        Dataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            throw new BusinessException("数据集不存在");
        }
        return dataset;
    }

    /**
     * 上传并解析文件
     */
    @Transactional(rollbackFor = Exception.class)
    public Dataset uploadFile(MultipartFile file, DatasetUploadDTO uploadDTO) {
        log.info("开始上传文件: {}, 名称: {}", file.getOriginalFilename(), uploadDTO.getName());

        // 检测文件类型
        String fileType = FileParserUtil.detectFileType(file);

        // 生成表名
        String tableName = generateTableName(uploadDTO.getName());

        // 解析文件
        ParseResult parseResult;
        try {
            parseResult = FileParserUtil.parseFile(file, uploadDTO.getHasHeader(), uploadDTO.getEncoding());
        } catch (IOException e) {
            log.error("文件解析失败", e);
            throw new BusinessException("文件解析失败: " + e.getMessage());
        }

        List<String> headers = parseResult.headers();
        List<Map<String, Object>> data = parseResult.data();

        if (headers.isEmpty()) {
            throw new BusinessException("未能解析到列信息");
        }

        // 保存数据集元信息
        Dataset dataset = new Dataset();
        dataset.setName(uploadDTO.getName());
        dataset.setDescription(uploadDTO.getDescription());
        dataset.setFileName(file.getOriginalFilename());
        dataset.setFileSize(file.getSize());
        dataset.setFileType(fileType);
        dataset.setTableName(tableName);
        dataset.setRowCount(data.size());
        dataset.setColumnCount(headers.size());
        dataset.setStatus(1);
        datasetMapper.insert(dataset);

        // 推断列类型并保存列信息
        List<DatasetColumn> columns = inferAndSaveColumns(dataset.getId(), headers, data);

        // 创建数据表
        dynamicTableService.createTable(tableName, columns);

        // 插入数据
        if (!data.isEmpty()) {
            dynamicTableService.insertData(tableName, columns, data);
        }

        log.info("数据集上传成功: {}, 表名: {}, 行数: {}, 列数: {}",
                dataset.getName(), tableName, data.size(), headers.size());

        return dataset;
    }

    /**
     * 预览数据
     */
    public DatasetPreviewVO previewData(Long datasetId, Integer page, Integer size) {
        Dataset dataset = getById(datasetId);
        List<DatasetColumn> columns = datasetColumnMapper.selectByDatasetId(datasetId);

        DatasetPreviewVO vo = new DatasetPreviewVO();
        vo.setPage(page);
        vo.setPageSize(size);

        // 设置列信息
        List<DatasetPreviewVO.ColumnInfoVO> columnInfos = columns.stream().map(col -> {
            DatasetPreviewVO.ColumnInfoVO info = new DatasetPreviewVO.ColumnInfoVO();
            info.setName(col.getColumnAlias());
            info.setType(col.getDataType());
            info.setLabel(col.getColumnName());
            return info;
        }).collect(Collectors.toList());
        vo.setColumns(columnInfos);

        // 查询数据
        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = datasetMapper.selectDynamicDataPage(dataset.getTableName(), offset, size);
        Long total = datasetMapper.selectDynamicDataCount(dataset.getTableName());

        vo.setRows(rows);
        vo.setTotal(total);

        return vo;
    }

    /**
     * 获取列信息
     */
    public List<DatasetColumn> getColumns(Long datasetId) {
        return datasetColumnMapper.selectByDatasetId(datasetId);
    }

    /**
     * 删除数据集
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDataset(Long id) {
        Dataset dataset = getById(id);

        // 删除数据表
        dynamicTableService.dropTable(dataset.getTableName());

        // 删除备份表
        try {
            List<Map<String, Object>> tables = datasetMapper.selectDynamicSql(
                    "SHOW TABLES LIKE '" + dataset.getTableName() + "_backup_%'");
            for (Map<String, Object> table : tables) {
                String tableName = table.values().iterator().next().toString();
                dynamicTableService.dropTable(tableName);
            }
        } catch (Exception e) {
            log.warn("删除备份表失败: {}", e.getMessage());
        }

        // 删除列信息
        datasetColumnMapper.delete(new LambdaQueryWrapper<DatasetColumn>()
                .eq(DatasetColumn::getDatasetId, id));

        // 删除数据集记录
        datasetMapper.deleteById(id);

        log.info("数据集已删除: {}", dataset.getName());
    }

    /**
     * 获取数据集的统计信息摘要(用于AI分析)
     */
    public Map<String, Object> getDatasetStatistics(Long datasetId) {
        Dataset dataset = getById(datasetId);
        List<DatasetColumn> columns = datasetColumnMapper.selectByDatasetId(datasetId);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("datasetName", dataset.getName());
        stats.put("rowCount", dataset.getRowCount());
        stats.put("columnCount", dataset.getColumnCount());
        stats.put("fileType", dataset.getFileType());

        List<Map<String, Object>> columnStats = new ArrayList<>();
        for (DatasetColumn col : columns) {
            Map<String, Object> colStat = new LinkedHashMap<>();
            colStat.put("name", col.getColumnName());
            colStat.put("type", col.getDataType());
            colStat.put("nullCount", col.getNullCount());
            colStat.put("uniqueCount", col.getUniqueCount());

            // 数值字段添加统计量
            if (col.getDataType().contains("INT") || col.getDataType().contains("DECIMAL")
                    || col.getDataType().contains("DOUBLE") || col.getDataType().contains("FLOAT")) {
                try {
                    Map<String, Object> colCalcStats = datasetMapper.selectColumnStatistics(
                            dataset.getTableName(), col.getColumnAlias());
                    if (colCalcStats != null) {
                        colStat.putAll(colCalcStats);
                    }
                } catch (Exception e) {
                    log.warn("计算列统计信息失败: {}", col.getColumnName());
                }
            }

            columnStats.add(colStat);
        }
        stats.put("columns", columnStats);

        return stats;
    }

    /**
     * 推断列类型并保存
     */
    private List<DatasetColumn> inferAndSaveColumns(Long datasetId, List<String> headers,
                                                     List<Map<String, Object>> data) {
        List<DatasetColumn> columns = new ArrayList<>();

        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            DatasetColumn col = new DatasetColumn();
            col.setDatasetId(datasetId);
            col.setColumnName(header);
            col.setColumnAlias(FileParserUtil.sanitizeColumnName(header));
            col.setSortOrder(i);
            col.setNullable(1);

            // 推断数据类型
            String inferredType = inferColumnType(header, data, i);
            col.setDataType(inferredType);
            col.setJavaType(mapJavaType(inferredType));

            // 统计信息
            calculateColumnStats(col, data);

            datasetColumnMapper.insert(col);
            columns.add(col);
        }

        return columns;
    }

    /**
     * 推断列的数据类型
     */
    private String inferColumnType(String header, List<Map<String, Object>> data, int colIndex) {
        int intCount = 0;
        int longCount = 0;
        int doubleCount = 0;
        int dateCount = 0;
        int boolCount = 0;
        int nullCount = 0;
        int total = 0;

        for (Map<String, Object> row : data) {
            Object value = row.get(header);
            if (value == null) {
                nullCount++;
                continue;
            }
            total++;

            if (value instanceof Boolean) {
                boolCount++;
            } else if (value instanceof Long) {
                longCount++;
            } else if (value instanceof Integer) {
                intCount++;
            } else if (value instanceof Double || value instanceof Float) {
                doubleCount++;
            } else if (value instanceof java.util.Date) {
                dateCount++;
            }
        }

        if (total == 0) return "VARCHAR(255)";

        double ratio = (double) total / data.size();

        if (boolCount == total) return "TINYINT";
        if (dateCount > total * 0.8) return "DATETIME";
        if (intCount + longCount == total) {
            // 检查是否需要BIGINT
            for (Map<String, Object> row : data) {
                Object value = row.get(header);
                if (value instanceof Long && Math.abs((Long) value) > Integer.MAX_VALUE) {
                    return "BIGINT";
                }
            }
            return "INT";
        }
        if ((intCount + longCount + doubleCount) == total || doubleCount > total * 0.5) {
            return "DECIMAL(20,6)";
        }

        // 估算字符串最大长度
        int maxLen = 0;
        for (Map<String, Object> row : data) {
            Object value = row.get(header);
            if (value != null) {
                maxLen = Math.max(maxLen, value.toString().length());
            }
        }
        if (maxLen <= 255) return "VARCHAR(255)";
        if (maxLen <= 1000) return "VARCHAR(1000)";
        return "TEXT";
    }

    /**
     * 映射Java类型
     */
    private String mapJavaType(String sqlType) {
        if (sqlType.contains("INT") || sqlType.contains("TINYINT")) return "Long";
        if (sqlType.contains("DECIMAL") || sqlType.contains("DOUBLE") || sqlType.contains("FLOAT")) return "Double";
        if (sqlType.contains("DATETIME") || sqlType.contains("DATE")) return "Date";
        return "String";
    }

    /**
     * 计算列统计信息
     */
    private void calculateColumnStats(DatasetColumn col, List<Map<String, Object>> data) {
        Set<Object> uniqueValues = new HashSet<>();
        int nullCount = 0;

        for (Map<String, Object> row : data) {
            Object value = row.get(col.getColumnName());
            if (value == null) {
                nullCount++;
            } else {
                uniqueValues.add(value);
            }
        }

        col.setUniqueCount(uniqueValues.size());
        col.setNullCount(nullCount);

        // 数值字段的极值
        if (col.getJavaType().equals("Long") || col.getJavaType().equals("Double")) {
            double sum = 0;
            int count = 0;
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;

            for (Map<String, Object> row : data) {
                Object value = row.get(col.getColumnName());
                if (value instanceof Number) {
                    double d = ((Number) value).doubleValue();
                    sum += d;
                    count++;
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
            }

            if (count > 0) {
                col.setMinValue(String.valueOf(min));
                col.setMaxValue(String.valueOf(max));
            }
        }
    }

    /**
     * 生成表名
     */
    private String generateTableName(String datasetName) {
        String base = FileParserUtil.sanitizeColumnName(datasetName).toLowerCase();
        return "data_" + base + "_" + System.currentTimeMillis();
    }
}
