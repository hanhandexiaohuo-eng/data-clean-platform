package com.dataclean.util;

import com.dataclean.exception.BusinessException;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;

/**
 * 文件解析工具类
 * 支持 CSV 和 Excel 文件解析
 */
@Slf4j
public class FileParserUtil {

    /**
     * 检测文件类型
     */
    public static String detectFileType(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new BusinessException("文件名不能为空");
        }
        String ext = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
        return switch (ext) {
            case "csv" -> "csv";
            case "xls", "xlsx" -> "excel";
            default -> throw new BusinessException("不支持的文件类型: " + ext);
        };
    }

    /**
     * 解析文件获取列名和数据
     */
    public static ParseResult parseFile(MultipartFile file, boolean hasHeader, String encoding) throws IOException {
        String fileType = detectFileType(file);
        return switch (fileType) {
            case "csv" -> parseCsv(file, hasHeader, encoding);
            case "excel" -> parseExcel(file, hasHeader);
            default -> throw new BusinessException("不支持的文件类型");
        };
    }

    /**
     * 解析 CSV 文件（流式读取，避免大文件内存溢出）
     */
    public static ParseResult parseCsv(MultipartFile file, boolean hasHeader, String encoding) throws IOException {
        List<String> headers = new ArrayList<>();
        List<Map<String, Object>> data = new ArrayList<>();

        try (Reader reader = new InputStreamReader(file.getInputStream(), encoding);
             CSVReader csvReader = new CSVReaderBuilder(reader).build()) {

            String[] firstLine = csvReader.readNext();
            if (firstLine == null) {
                throw new BusinessException("CSV 文件为空");
            }

            if (hasHeader) {
                for (String h : firstLine) {
                    headers.add(sanitizeColumnName(h));
                }
                // 去重：处理空列名等导致的重复
                headers = new ArrayList<>(deduplicateHeaders(headers));
            } else {
                for (int i = 0; i < firstLine.length; i++) {
                    headers.add("column_" + (i + 1));
                }
                // 第一行是数据，需要处理
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int j = 0; j < Math.min(firstLine.length, headers.size()); j++) {
                    rowMap.put(headers.get(j), inferType(firstLine[j]));
                }
                for (int j = firstLine.length; j < headers.size(); j++) {
                    rowMap.put(headers.get(j), null);
                }
                data.add(rowMap);
            }

            // 流式读取剩余数据行
            String[] row;
            while ((row = csvReader.readNext()) != null) {
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int j = 0; j < Math.min(row.length, headers.size()); j++) {
                    rowMap.put(headers.get(j), inferType(row[j]));
                }
                for (int j = row.length; j < headers.size(); j++) {
                    rowMap.put(headers.get(j), null);
                }
                data.add(rowMap);
            }
        } catch (CsvException e) {
            throw new RuntimeException("CSV解析失败: " + e.getMessage(), e);
        }

        return new ParseResult(headers, data, data.size());
    }

    /**
     * 解析 Excel 文件
     */
    public static ParseResult parseExcel(MultipartFile file, boolean hasHeader) throws IOException {
        List<String> headers = new ArrayList<>();
        List<Map<String, Object>> data = new ArrayList<>();

        Workbook workbook;
        String filename = file.getOriginalFilename();
        try (InputStream is = file.getInputStream()) {
            if (filename != null && filename.endsWith(".xls")) {
                workbook = new HSSFWorkbook(is);
            } else {
                workbook = new XSSFWorkbook(is);
            }
        }

        Sheet sheet = workbook.getSheetAt(0);
        int lastRow = sheet.getLastRowNum();
        if (lastRow < 0) {
            throw new BusinessException("Excel 文件为空");
        }

        // 确定最大列数
        int maxCols = 0;
        for (int i = 0; i <= lastRow; i++) {
            Row row = sheet.getRow(i);
            if (row != null) {
                maxCols = Math.max(maxCols, row.getLastCellNum());
            }
        }

        int startRow = 0;
        if (hasHeader) {
            Row headerRow = sheet.getRow(0);
            for (int i = 0; i < maxCols; i++) {
                Cell cell = headerRow != null ? headerRow.getCell(i) : null;
                String name = cell != null ? getCellStringValue(cell) : "column_" + (i + 1);
                headers.add(sanitizeColumnName(name));
            }
            // 去重：处理空列名等导致的重复
            headers = new ArrayList<>(deduplicateHeaders(headers));
            startRow = 1;
        } else {
            for (int i = 0; i < maxCols; i++) {
                headers.add("column_" + (i + 1));
            }
        }

        // 解析数据
        for (int i = startRow; i <= lastRow; i++) {
            Row row = sheet.getRow(i);
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (int j = 0; j < maxCols; j++) {
                String key = headers.get(j);
                if (row == null) {
                    rowMap.put(key, null);
                    continue;
                }
                Cell cell = row.getCell(j);
                rowMap.put(key, getCellValue(cell));
            }
            data.add(rowMap);
        }

        workbook.close();
        return new ParseResult(headers, data, data.size());
    }

    /**
     * 获取单元格字符串值
     */
    private static String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                }
                yield String.valueOf(cell.getNumericCellValue());
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    /**
     * 获取单元格值(自动推断类型)
     */
    private static Object getCellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield (long) val;
                }
                yield val;
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case BLANK -> null;
            default -> null;
        };
    }

    /**
     * 推断数据类型
     */
    private static Object inferType(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        value = value.trim();

        // 尝试整数
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }

        // 尝试小数
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }

        // 布尔值
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }

        // 字符串
        return value;
    }

    /**
     * 标准化列名
     */
    public static String sanitizeColumnName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "unnamed";
        }
        // 去除前后空格
        name = name.trim();
        // 替换特殊字符为下划线
        name = name.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");
        // 确保不以数字开头
        if (name.matches("^\\d.*")) {
            name = "col_" + name;
        }
        // 限制长度
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        return name;
    }

    /**
     * 对列名列表进行去重，重复的列名追加 _2, _3 等后缀
     */
    public static List<String> deduplicateHeaders(List<String> headers) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> countMap = new LinkedHashMap<>();
        for (String h : headers) {
            int count = countMap.merge(h, 1, Integer::sum);
            if (count == 1) {
                result.add(h);
            } else {
                // 截断以确保最终列名不超过 64 字符
                String suffix = "_" + count;
                String base = h.length() + suffix.length() > 64
                        ? h.substring(0, 64 - suffix.length()) : h;
                result.add(base + suffix);
            }
        }
        return result;
    }

    /**
     * 推断SQL列类型
     */
    public static String inferSqlType(Object value) {
        if (value == null) return "VARCHAR(255)";
        if (value instanceof Long) return "BIGINT";
        if (value instanceof Integer) return "INT";
        if (value instanceof Double) return "DECIMAL(20,6)";
        if (value instanceof Float) return "DECIMAL(20,6)";
        if (value instanceof Boolean) return "TINYINT";
        if (value instanceof Date) return "DATETIME";
        return "VARCHAR(500)";
    }

    /**
     * 解析结果
     */
    public record ParseResult(List<String> headers, List<Map<String, Object>> data, int rowCount) {
    }
}
