package com.dataclean.util;

import com.dataclean.exception.BusinessException;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 文件解析工具类
 * 支持 CSV 和 Excel 文件解析。
 *
 * <p>安全约束：
 * <ul>
 *   <li>文件类型同时校验扩展名与文件头，不再只信任后缀；</li>
 *   <li>限制文件大小、行数、列数、工作表数量；</li>
 *   <li>文件名只做展示，统一净化，不参与路径拼接；</li>
 *   <li>错误信息不暴露内部路径或堆栈。</li>
 * </ul>
 */
@Slf4j
public class FileParserUtil {

    public static final int MAX_ROWS = 500_000;
    public static final int MAX_COLUMNS = 1_000;
    public static final int MAX_EXCEL_SHEETS = 20;
    public static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final int SNIFF_BYTES = 512;

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static final Set<String> ALLOWED_ENCODINGS = Set.of("UTF-8", "GBK", "GB18030");

    static {
        // 防御压缩炸弹：限制单个压缩条目解压后的体积
        ZipSecureFile.setMaxEntrySize(200L * 1024 * 1024);
    }

    /**
     * 检测文件类型：扩展名与文件头必须同时匹配。
     */
    public static String detectFileType(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(400, "文件大小超过上限（100MB）");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }

        String ext = extensionOf(originalFilename);
        byte[] head = readHead(file);

        return switch (ext) {
            case "csv" -> {
                if (startsWith(head, ZIP_MAGIC) || startsWith(head, OLE2_MAGIC)) {
                    throw new BusinessException(400, "文件内容与扩展名不匹配");
                }
                if (containsNul(head)) {
                    throw new BusinessException(400, "CSV 文件包含二进制内容");
                }
                yield "csv";
            }
            case "xlsx" -> {
                if (!startsWith(head, ZIP_MAGIC)) {
                    throw new BusinessException(400, "文件内容与扩展名不匹配");
                }
                yield "excel";
            }
            case "xls" -> {
                if (!startsWith(head, OLE2_MAGIC)) {
                    throw new BusinessException(400, "文件内容与扩展名不匹配");
                }
                yield "excel";
            }
            default -> throw new BusinessException(400, "仅支持 CSV / XLS / XLSX 文件");
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
            default -> throw new BusinessException(400, "不支持的文件类型");
        };
    }

    /**
     * 解析 CSV 文件（流式读取，并在超限时立即中止）
     */
    public static ParseResult parseCsv(MultipartFile file, boolean hasHeader, String encoding) throws IOException {
        Charset charset = resolveCharset(encoding);
        return parseCsv(new InputStreamReader(file.getInputStream(), charset), hasHeader);
    }

    /**
     * 解析 CSV 字符流（供演示数据等内部资源复用同一套安全解析与限制）
     */
    public static ParseResult parseCsv(Reader input, boolean hasHeader) throws IOException {
        List<String> headers = new ArrayList<>();
        List<Map<String, Object>> data = new ArrayList<>();

        try (CSVReader csvReader = new CSVReaderBuilder(input).build()) {

            String[] firstLine = csvReader.readNext();
            if (firstLine == null) {
                throw new BusinessException(400, "CSV 文件为空");
            }
            // UTF-8 BOM 会被解码为 \uFEFF 并混入首列：必须在解析列名/数据前剥离
            if (firstLine.length > 0) {
                firstLine[0] = stripBom(firstLine[0]);
            }
            if (firstLine.length > MAX_COLUMNS) {
                throw new BusinessException(400, "列数超过上限: " + MAX_COLUMNS);
            }

            if (hasHeader) {
                for (String h : firstLine) {
                    headers.add(sanitizeColumnName(h));
                }
                headers = new ArrayList<>(deduplicateHeaders(headers));
            } else {
                for (int i = 0; i < firstLine.length; i++) {
                    headers.add("column_" + (i + 1));
                }
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int j = 0; j < Math.min(firstLine.length, headers.size()); j++) {
                    rowMap.put(headers.get(j), inferType(firstLine[j]));
                }
                for (int j = firstLine.length; j < headers.size(); j++) {
                    rowMap.put(headers.get(j), null);
                }
                data.add(rowMap);
            }

            String[] row;
            while ((row = csvReader.readNext()) != null) {
                if (data.size() >= MAX_ROWS) {
                    throw new BusinessException(400, "数据行数超过上限: " + MAX_ROWS);
                }
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
            throw new BusinessException(400, "CSV 解析失败，请检查文件格式");
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(400, "CSV 读取失败，请检查文件编码");
        }

        return new ParseResult(headers, data, data.size());
    }

    /**
     * 解析 Excel 文件（只读取第一个工作表）
     */
    public static ParseResult parseExcel(MultipartFile file, boolean hasHeader) throws IOException {
        List<String> headers = new ArrayList<>();
        List<Map<String, Object>> data = new ArrayList<>();
        String filename = file.getOriginalFilename();

        Workbook workbook = null;
        try (InputStream is = file.getInputStream()) {
            boolean isXls = filename != null && filename.toLowerCase().endsWith(".xls");
            workbook = isXls ? new HSSFWorkbook(is) : new XSSFWorkbook(is);

            if (workbook.getNumberOfSheets() > MAX_EXCEL_SHEETS) {
                throw new BusinessException(400, "工作表数量超过上限: " + MAX_EXCEL_SHEETS);
            }

            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            if (lastRow < 0) {
                throw new BusinessException(400, "Excel 文件为空");
            }
            if (lastRow + 1 > MAX_ROWS + 1) {
                throw new BusinessException(400, "数据行数超过上限: " + MAX_ROWS);
            }

            int maxCols = 0;
            for (int i = 0; i <= lastRow; i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    maxCols = Math.max(maxCols, row.getLastCellNum());
                }
            }
            if (maxCols > MAX_COLUMNS) {
                throw new BusinessException(400, "列数超过上限: " + MAX_COLUMNS);
            }

            int startRow = 0;
            if (hasHeader) {
                Row headerRow = sheet.getRow(0);
                for (int i = 0; i < maxCols; i++) {
                    Cell cell = headerRow != null ? headerRow.getCell(i) : null;
                    String name = cell != null ? getCellStringValue(cell) : "column_" + (i + 1);
                    headers.add(sanitizeColumnName(name));
                }
                headers = new ArrayList<>(deduplicateHeaders(headers));
                startRow = 1;
            } else {
                for (int i = 0; i < maxCols; i++) {
                    headers.add("column_" + (i + 1));
                }
            }

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
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "Excel 解析失败，请检查文件格式");
        } finally {
            if (workbook != null) {
                try {
                    workbook.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响解析结果
                }
            }
        }

        return new ParseResult(headers, data, data.size());
    }

    /**
     * 净化原始文件名：仅保留安全字符，防止路径穿越与危险字符。
     */
    public static String sanitizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "upload";
        }
        String name = originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
        name = name.replaceAll("\\.{2,}", ".");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "upload";
        }
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, Math.min(120 - ext.length(), name.length())) + ext;
        }
        return name;
    }

    /**
     * CSV 公式注入防护：导出/展示时对危险前缀做转义。
     */
    public static String escapeCsvFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private static Charset resolveCharset(String encoding) {
        String name = (encoding == null || encoding.isBlank()) ? "UTF-8" : encoding.trim();
        String upper = name.toUpperCase(Locale.ROOT);
        if (!ALLOWED_ENCODINGS.contains(upper)) {
            throw new BusinessException(400, "不支持的编码: " + encoding);
        }
        return Charset.forName(upper);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new BusinessException(400, "文件缺少扩展名");
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            return is.readNBytes(SNIFF_BYTES);
        } catch (IOException e) {
            throw new BusinessException(400, "文件读取失败");
        }
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data == null || data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNul(byte[] data) {
        if (data == null) {
            return false;
        }
        for (byte b : data) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

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

    private static Object inferType(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        value = value.trim();

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
        }

        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }

        return value;
    }

    /**
     * 标准化列名
     */
    public static String sanitizeColumnName(String name) {
        if (name == null) {
            return "unnamed";
        }
        // 防御性剥离 BOM（含 Excel 复制粘贴产生的 \uFEFF），避免进入真实字段名
        name = name.replace("\uFEFF", "").trim();
        if (name.isEmpty()) {
            return "unnamed";
        }
        name = name.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "_");
        if (name.matches("^\\d.*")) {
            name = "col_" + name;
        }
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        return name;
    }

    /** 去除字符串开头的 UTF-8 BOM 字符，其余内容保持不变。 */
    private static String stripBom(String value) {
        if (value != null && !value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
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
