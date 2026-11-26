package com.dataclean.service;

import com.dataclean.config.JobProperties;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.exception.BusinessException;
import com.dataclean.job.JobCancelledException;
import com.dataclean.job.JobStatus;
import com.dataclean.mapper.AsyncJobMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.util.FileParserUtil;
import com.dataclean.util.SqlGuard;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVParserBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import javax.xml.parsers.SAXParserFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流式导入器：CSV 逐行解析、分批写入；XLSX 使用 SAX 事件模型，不把整表载入内存。
 *
 * <p>导入失败/取消时由调用方负责删除半成品物理表；本类不接触元数据表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamingImportService {

    private static final int MAX_COLUMNS = 500;
    private static final int MAX_CELL_LENGTH = 32768;
    private static final int SAMPLE_ROWS = 200;

    static {
        // 防御压缩炸弹：限制解压膨胀比与单条目大小
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(1024L * 1024 * 1024);
    }

    /** 单工作表最大行数与工作簿最大工作表数（来自 app.upload 配置） */
    @Value("${app.upload.max-rows:500000}")
    private int maxRows;
    @Value("${app.upload.max-excel-sheets:20}")
    private int maxSheets;

    private final DatasetMapper datasetMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final DynamicTableService dynamicTableService;
    private final AsyncJobMapper jobMapper;
    private final JobProperties jobProperties;

    /**
     * 导入入口：按扩展名分流。
     *
     * @return 导入行数
     */
    public long importFile(File file, String fileType, boolean hasHeader, String encoding,
                           Dataset dataset, Long jobId) throws Exception {
        String type = fileType == null ? "" : fileType.toLowerCase(Locale.ROOT);
        if ("xlsx".equals(type)) {
            return importXlsx(file, hasHeader, dataset, jobId);
        }
        if ("xls".equals(type)) {
            throw new BusinessException(400, "旧版 .xls 请先另存为 .xlsx 或 CSV 后再导入");
        }
        return importCsv(file, hasHeader, encoding, dataset, jobId);
    }

    // ------------------------------------------------------------------ CSV

    private long importCsv(File file, boolean hasHeader, String encoding,
                           Dataset dataset, Long jobId) throws Exception {
        Charset charset = Charset.forName(encoding == null || encoding.isBlank() ? "UTF-8" : encoding);
        long fileSize = Math.max(1L, file.length());

        try (CountingInputStream counting = new CountingInputStream(new BufferedInputStream(new FileInputStream(file)));
             Reader reader = new InputStreamReader(counting, charset);
             CSVReader csv = new CSVReaderBuilder(reader)
                     .withCSVParser(new CSVParserBuilder().withSeparator(',').build())
                     .build()) {

            String[] first = csv.readNext();
            if (first == null || first.length == 0) {
                throw new BusinessException(400, "文件为空或缺少表头");
            }
            if (first.length > MAX_COLUMNS) {
                throw new BusinessException(400, "列数超过上限（" + MAX_COLUMNS + "）");
            }
            String[] header = hasHeader ? first : null;
            List<String> names = buildHeaders(header, first.length);

            List<String[]> sample = new ArrayList<>();
            String[] row;
            while (sample.size() < SAMPLE_ROWS && (row = csv.readNext()) != null) {
                if (!isBlankRow(row)) {
                    sample.add(row);
                }
            }

            List<DatasetColumn> columns = buildColumns(names, sample);
            prepareTable(dataset, columns);
            long rows = writeCsvRows(csv, sample, columns, dataset, jobId, counting, fileSize);
            return rows;
        }
    }

    private long writeCsvRows(CSVReader csv, List<String[]> sample, List<DatasetColumn> columns,
                              Dataset dataset, Long jobId, CountingInputStream counting, long fileSize) throws Exception {
        long processed = 0;
        List<Map<String, Object>> chunk = new ArrayList<>(jobProperties.getChunkSize());
        String phase = "WRITING";
        for (String[] values : sample) {
            chunk.add(toRow(columns, values));
            processed++;
        }
        flush(chunk, columns, dataset, jobId, processed, parseProgress(counting, fileSize));
        tick(dataset, jobId, phase, processed, parseProgress(counting, fileSize));

        String[] row;
        while ((row = csv.readNext()) != null) {
            if (isBlankRow(row)) {
                continue;
            }
            if (row.length > MAX_COLUMNS) {
                throw new BusinessException(400, "列数超过上限");
            }
            chunk.add(toRow(columns, row));
            processed++;
            if (processed > maxRows) {
                throw new BusinessException(400, "数据行数超过上限（" + maxRows + "）");
            }
            if (chunk.size() >= jobProperties.getChunkSize()) {
                flush(chunk, columns, dataset, jobId, processed, parseProgress(counting, fileSize));
                tick(dataset, jobId, phase, processed, parseProgress(counting, fileSize));
            }
        }
        if (!chunk.isEmpty()) {
            flush(chunk, columns, dataset, jobId, processed, 95);
        }
        if (processed == 0) {
            throw new BusinessException(400, "文件没有可导入的数据行");
        }
        return processed;
    }

    // ----------------------------------------------------------------- XLSX

    private long importXlsx(File file, boolean hasHeader, Dataset dataset, Long jobId) throws Exception {
        try (OPCPackage pkg = OPCPackage.open(file, PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            var sheets = reader.getSheetsData();
            if (!sheets.hasNext()) {
                throw new BusinessException(400, "Excel 中没有可读取的工作表");
            }
            // 仅解析第一个工作表，但先统计工作表数量并关闭其余流，避免超限工作簿
            InputStream firstSheet = null;
            int sheetCount = 0;
            while (sheets.hasNext()) {
                InputStream next = sheets.next();
                sheetCount++;
                if (sheetCount == 1) {
                    firstSheet = next;
                } else {
                    next.close();
                }
            }
            if (sheetCount > maxSheets) {
                if (firstSheet != null) {
                    firstSheet.close();
                }
                throw new BusinessException(400, "工作表数量超过上限（" + maxSheets + "）");
            }
            InputStream sheet = firstSheet;
            SheetConsumer consumer = new SheetConsumer(hasHeader, dataset, jobId);
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XMLReader parser = factory.newSAXParser().getXMLReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(styles, strings, consumer, false));
            try (InputStream in = sheet) {
                parser.parse(new InputSource(in));
            }
            consumer.finish();
            return consumer.rows;
        }
    }

    /** SAX 行消费者：每累积一个批次写入一次数据库，并做取消检查。 */
    private final class SheetConsumer implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final boolean hasHeader;
        private final Dataset dataset;
        private final Long jobId;
        private final List<String> cells = new ArrayList<>();
        private List<String> headers;
        private List<DatasetColumn> columns;
        private List<Map<String, Object>> chunk = new ArrayList<>(jobProperties.getChunkSize());
        private long rows;
        private long physicalRows;

        SheetConsumer(boolean hasHeader, Dataset dataset, Long jobId) {
            this.hasHeader = hasHeader;
            this.dataset = dataset;
            this.jobId = jobId;
        }

        @Override
        public void startRow(int rowNum) {
            cells.clear();
            if (physicalRows++ % 5000 == 0) {
                checkCancel();
            }
        }

        @Override
        public void endRow(int rowNum) {
            if (cells.isEmpty()) {
                return;
            }
            if (headers == null) {
                List<String> raw = new ArrayList<>(cells);
                if (!hasHeader) {
                    headers = buildHeaders(null, raw.size());
                    // 无表头时首行即数据
                    if (columns == null) {
                        columns = buildColumns(headers, java.util.Collections.singletonList(raw.toArray(new String[0])));
                        prepareTable(dataset, columns);
                    }
                    chunk.add(toRow(columns, raw.toArray(new String[0])));
                    rows++;
                    return;
                }
                headers = buildHeaders(raw.toArray(new String[0]), raw.size());
                columns = buildColumns(headers, List.of());
                prepareTable(dataset, columns);
                return;
            }
            chunk.add(toRow(columns, cells.toArray(new String[0])));
            rows++;
            if (rows > maxRows) {
                throw new BusinessException(400, "数据行数超过上限（" + maxRows + "）");
            }
            if (chunk.size() >= jobProperties.getChunkSize()) {
                flush(chunk, columns, dataset, jobId, rows, (int) Math.min(94L, rows / 5000));
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue != null && formattedValue.length() > MAX_CELL_LENGTH) {
                throw new BusinessException(400, "单元格内容超过长度上限");
            }
            cells.add(formattedValue == null ? "" : formattedValue);
        }

        void finish() {
            if (headers == null) {
                throw new BusinessException(400, "Excel 表头为空");
            }
            if (!chunk.isEmpty()) {
                flush(chunk, columns, dataset, jobId, rows, 95);
            }
            if (rows == 0) {
                throw new BusinessException(400, "文件没有可导入的数据行");
            }
        }

        private void checkCancel() {
            var latest = jobMapper.selectById(jobId);
            if (latest == null || JobStatus.CANCEL_REQUESTED.equals(latest.getStatus())
                    || JobStatus.CANCELLED.equals(latest.getStatus())) {
                throw new JobCancelledException();
            }
        }
    }

    // ---------------------------------------------------------------- 公共

    private void prepareTable(Dataset dataset, List<DatasetColumn> columns) {
        String table = SqlGuard.requireTableName(dataset.getTableName());
        datasetMapper.executeDynamicSql("DROP TABLE IF EXISTS `" + table + "`");
        dynamicTableService.createTable(table, columns);

        datasetColumnMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DatasetColumn>()
                .eq(DatasetColumn::getDatasetId, dataset.getId()));
        int order = 0;
        for (DatasetColumn column : columns) {
            column.setDatasetId(dataset.getId());
            column.setSortOrder(order++);
            datasetColumnMapper.insert(column);
        }
        dataset.setColumnCount(columns.size());
        datasetMapper.updateById(dataset);
    }

    private void flush(List<Map<String, Object>> chunk, List<DatasetColumn> columns,
                       Dataset dataset, Long jobId, long processed, int progress) {
        if (chunk.isEmpty()) {
            return;
        }
        dynamicTableService.insertData(dataset.getTableName(), columns, new ArrayList<>(chunk));
        chunk.clear();
        jobMapper.updateProgress(jobId, "WRITING", progress, processed, 0L);
    }

    private void tick(Dataset dataset, Long jobId, String phase, long processed, int progress) {
        String status = jobMapper.selectById(jobId).getStatus();
        if (JobStatus.CANCEL_REQUESTED.equals(status)) {
            throw new JobCancelledException();
        }
        jobMapper.updateProgress(jobId, phase, progress, processed, 0L);
    }

    private int parseProgress(CountingInputStream counting, long fileSize) {
        return (int) Math.min(94, counting.getCount() * 94 / fileSize);
    }

    private List<String> buildHeaders(String[] header, int width) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            String raw = header == null || i >= header.length ? "" : header[i];
            if (i == 0 && raw != null) {
                raw = raw.replace("\uFEFF", "");
            }
            names.add(FileParserUtil.sanitizeColumnName(raw));
        }
        return FileParserUtil.deduplicateHeaders(names);
    }

    private List<DatasetColumn> buildColumns(List<String> names, List<String[]> sample) {
        List<DatasetColumn> columns = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String type = inferType(names.get(i), i, sample);
            DatasetColumn column = new DatasetColumn();
            column.setColumnName(names.get(i));
            column.setColumnAlias(names.get(i));
            column.setDataType(type);
            column.setJavaType(javaType(type));
            column.setNullable(1);
            columns.add(column);
        }
        return columns;
    }

    private String inferType(String name, int index, List<String[]> sample) {
        boolean numericInt = true;
        boolean numericDecimal = true;
        boolean bool = true;
        boolean any = false;
        int maxLength = 0;
        for (String[] row : sample) {
            if (index >= row.length) {
                continue;
            }
            String value = row[index] == null ? "" : row[index].trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.length() > MAX_CELL_LENGTH) {
                throw new BusinessException(400, "单元格内容超过长度上限");
            }
            any = true;
            maxLength = Math.max(maxLength, value.length());
            if (numericInt) {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    numericInt = false;
                }
            }
            if (numericDecimal) {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    numericDecimal = false;
                }
            }
            if (bool && !("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))) {
                bool = false;
            }
        }
        if (!any) {
            return "VARCHAR(255)";
        }
        if (bool) {
            return "TINYINT";
        }
        if (numericInt) {
            return "BIGINT";
        }
        if (numericDecimal) {
            return "DECIMAL(20,6)";
        }
        // 抽样只能代表前 200 行，超出 VARCHAR(255) 的文本列直接使用 TEXT，
        // 避免后续批次出现更长内容时触发 “Data too long” 导致整次导入失败。
        return maxLength <= 255 ? "VARCHAR(255)" : "TEXT";
    }

    private String javaType(String sqlType) {
        return switch (sqlType) {
            case "BIGINT" -> "long";
            case "DECIMAL(20,6)" -> "double";
            case "TINYINT" -> "int";
            default -> "string";
        };
    }

    private Map<String, Object> toRow(List<DatasetColumn> columns, String[] values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String raw = i < values.length ? values[i] : "";
            if (raw != null && raw.length() > MAX_CELL_LENGTH) {
                throw new BusinessException(400, "单元格内容超过长度上限");
            }
            row.put(columns.get(i).getColumnName(), coerce(raw, columns.get(i).getDataType()));
        }
        return row;
    }

    private Object coerce(String value, String type) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return switch (type) {
                case "BIGINT" -> Long.parseLong(trimmed);
                case "DECIMAL(20,6)" -> Double.parseDouble(trimmed);
                case "TINYINT" -> {
                    if ("true".equalsIgnoreCase(trimmed)) {
                        yield Integer.valueOf(1);
                    }
                    if ("false".equalsIgnoreCase(trimmed)) {
                        yield Integer.valueOf(0);
                    }
                    // 布尔列中出现无法识别的值：保留为空，避免三元表达式自动拆箱导致空指针
                    yield null;
                }
                default -> trimmed;
            };
        } catch (NumberFormatException e) {
            // 抽样后出现不符合推断类型的值：保留原值，由数据库列类型决定存储
            return trimmed;
        }
    }

    private boolean isBlankRow(String[] row) {
        if (row == null || row.length == 0) {
            return true;
        }
        for (String value : row) {
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 统计已读字节，用于大文件进度估算。 */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws java.io.IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        long getCount() {
            return count;
        }
    }
}
