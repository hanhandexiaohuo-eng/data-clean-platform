package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataclean.config.AppMode;
import com.dataclean.config.JobProperties;
import com.dataclean.config.UploadStorageConfig;
import com.dataclean.dto.AsyncImportVO;
import com.dataclean.dto.DatasetPreviewVO;
import com.dataclean.dto.DatasetUploadDTO;
import com.dataclean.entity.AsyncJob;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.entity.SavedChart;
import com.dataclean.exception.BusinessException;
import com.dataclean.job.JobStatus;
import com.dataclean.mapper.AsyncJobMapper;
import com.dataclean.mapper.BackupTableRecordMapper;
import com.dataclean.mapper.ChartViewMapper;
import com.dataclean.mapper.CleanRuleMapper;
import com.dataclean.mapper.DashboardItemMapper;
import com.dataclean.mapper.DataQualityReportMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.DatasetVersionRuleMapper;
import com.dataclean.mapper.SavedChartMapper;
import com.dataclean.mapper.StatsCacheMapper;
import com.dataclean.metrics.RuntimeMetrics;
import com.dataclean.util.FileParserUtil;
import com.dataclean.util.FileParserUtil.ParseResult;
import com.dataclean.util.RequestContext;
import com.dataclean.util.SqlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final DatasetVersionMapper datasetVersionMapper;
    private final AsyncJobMapper asyncJobMapper;
    private final StatsCacheMapper statsCacheMapper;
    private final DatasetVersionRuleMapper datasetVersionRuleMapper;
    private final DataQualityReportMapper dataQualityReportMapper;
    private final CleanRuleMapper cleanRuleMapper;
    private final SavedChartMapper savedChartMapper;
    private final DashboardItemMapper dashboardItemMapper;
    private final ChartViewMapper chartViewMapper;
    private final BackupTableRecordMapper backupTableRecordMapper;
    private final DynamicTableService dynamicTableService;
    private final OwnershipService ownershipService;
    private final PublicQuotaService publicQuotaService;
    private final AppMode appMode;
    private final FileScanService fileScanService;
    private final RuntimeMetrics metrics;
    private final JobProperties jobProperties;
    private final JobService jobService;
    private final VersionService versionService;
    private final UploadStorageConfig uploadStorageConfig;
    private final AnalysisService analysisService;

    /**
     * 分页查询数据集列表（公开模式只返回演示数据与本会话数据）
     */
    public Page<Dataset> pageList(Integer page, Integer size, String keyword) {
        LambdaQueryWrapper<Dataset> wrapper = ownershipService.visibleDatasetWrapper();
        // 0=异步导入处理中，1=可用；失败（2）不进入列表，由任务中心展示与重试
        wrapper.in(Dataset::getStatus, 0, 1);
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w.like(Dataset::getName, keyword)
                    .or()
                    .like(Dataset::getDescription, keyword));
        }
        wrapper.orderByDesc(Dataset::getCreatedAt);
        return datasetMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 根据ID查询数据集（不可见统一 404）
     */
    public Dataset getById(Long id) {
        return ownershipService.requireDataset(id);
    }

    /**
     * 上传并解析文件
     */
    @Transactional(rollbackFor = Exception.class)
    public Dataset uploadFile(MultipartFile file, DatasetUploadDTO uploadDTO) {
        try {
            return doUploadFile(file, uploadDTO);
        } catch (BusinessException e) {
            // 只统计被拒绝的上传次数，不记录文件名、内容或来源
            metrics.uploadRejected();
            throw e;
        }
    }

    private Dataset doUploadFile(MultipartFile file, DatasetUploadDTO uploadDTO) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        // 公开模式：先校验数据集数量、总存储与单文件大小
        publicQuotaService.checkUpload(file.getSize());
        if (uploadDTO == null || uploadDTO.getName() == null || uploadDTO.getName().trim().isEmpty()) {
            throw new BusinessException(400, "数据集名称不能为空");
        }
        if (uploadDTO.getName().length() > 100) {
            throw new BusinessException(400, "数据集名称过长");
        }

        // 只保留安全的文件名用于展示，不参与任何路径拼接
        String safeFileName = FileParserUtil.sanitizeFileName(file.getOriginalFilename());
        log.info("开始上传文件: {}, 名称: {}", safeFileName, uploadDTO.getName());

        // 检测文件类型（扩展名 + 文件头双重校验）
        String fileType = FileParserUtil.detectFileType(file);

        // 恶意文件扫描：解析、建表与持久化之前完成；失败/超时/不可用一律拒绝
        try {
            fileScanService.scan(file, safeFileName, fileType);
        } catch (RuntimeException e) {
            metrics.uploadScanFailed();
            throw e;
        }

        // 生成表名
        String tableName = generateTableName(uploadDTO.getName());

        // 解析文件
        ParseResult parseResult;
        try {
            parseResult = FileParserUtil.parseFile(file, uploadDTO.getHasHeader(), uploadDTO.getEncoding());
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            // 不向前端暴露内部路径/堆栈
            log.error("文件解析失败: {}", e.getClass().getSimpleName());
            throw new BusinessException(400, "文件解析失败，请检查文件格式与编码");
        }

        List<String> headers = parseResult.headers();
        List<Map<String, Object>> data = parseResult.data();

        if (headers.isEmpty()) {
            throw new BusinessException(400, "未能解析到列信息");
        }
        if (headers.size() > FileParserUtil.MAX_COLUMNS) {
            throw new BusinessException(400, "列数超过上限: " + FileParserUtil.MAX_COLUMNS);
        }
        if (data.size() > FileParserUtil.MAX_ROWS) {
            throw new BusinessException(400, "行数超过上限: " + FileParserUtil.MAX_ROWS);
        }
        // 公开模式行/列上限更严格
        publicQuotaService.checkRowsAndColumns(data.size(), headers.size());

        String ownerType = appMode.isPublic() ? OwnershipService.OWNER_ANON : OwnershipService.OWNER_PRIVATE;
        Long anonSessionId = appMode.isPublic() ? RequestContext.requireSessionId() : null;
        Dataset saved = persistDataset(uploadDTO.getName(), uploadDTO.getDescription(), safeFileName,
                file.getSize(), fileType, tableName, headers, data, ownerType, anonSessionId);
        // 同步导入的数据集同样登记初始版本，后续清洗/恢复与异步导入走同一套版本模型
        versionService.ensureInitialVersion(saved, DatasetVersion.SOURCE_IMPORT);
        metrics.uploadSuccess();
        return saved;
    }

    /** 是否超过异步导入阈值（字节数）；公开/私有共用同一阈值配置。 */
    public boolean shouldImportAsync(MultipartFile file) {
        return jobProperties.isEnabled() && file != null
                && !file.isEmpty() && file.getSize() >= jobProperties.getAsyncImportBytes();
    }

    /**
     * 提交异步导入任务：文件先落临时卷，元数据与任务持久化后立即返回。
     *
     * <p>数据集以 status=0（处理中）占位；任务完成才写入数据表并切换为 status=1。
     * 任务失败/取消时由执行器删除占位表与临时文件。
     */
    public AsyncImportVO submitAsyncImport(MultipartFile file, DatasetUploadDTO uploadDTO) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        publicQuotaService.checkUpload(file.getSize());
        if (uploadDTO == null || uploadDTO.getName() == null || uploadDTO.getName().trim().isEmpty()) {
            throw new BusinessException(400, "数据集名称不能为空");
        }
        if (uploadDTO.getName().length() > 100) {
            throw new BusinessException(400, "数据集名称过长");
        }

        String safeFileName = FileParserUtil.sanitizeFileName(file.getOriginalFilename());
        String fileType = FileParserUtil.detectFileType(file);
        try {
            fileScanService.scan(file, safeFileName, fileType);
        } catch (RuntimeException e) {
            metrics.uploadScanFailed();
            throw e;
        }

        Path tempFile = null;
        Dataset dataset = null;
        try {
            // 临时文件名由服务端生成，不使用客户端文件名拼接路径
            String name = UUID.randomUUID().toString().replace("-", "") + "." + fileType;
            tempFile = uploadStorageConfig.tempDir().resolve(name).toAbsolutePath().normalize();
            file.transferTo(tempFile);
            if (Files.size(tempFile) <= 0) {
                throw new BusinessException(400, "上传文件为空");
            }

            String tableName = generateTableName(uploadDTO.getName());
            String ownerType = appMode.isPublic() ? OwnershipService.OWNER_ANON : OwnershipService.OWNER_PRIVATE;
            Long anonSessionId = appMode.isPublic() ? RequestContext.requireSessionId() : null;

            dataset = new Dataset();
            dataset.setName(uploadDTO.getName());
            dataset.setDescription(uploadDTO.getDescription());
            dataset.setFileName(safeFileName);
            dataset.setFileSize(file.getSize());
            dataset.setFileType(fileType);
            dataset.setTableName(tableName);
            dataset.setRowCount(0);
            dataset.setColumnCount(0);
            dataset.setStatus(0);
            dataset.setOwnerType(ownerType);
            dataset.setAnonSessionId(anonSessionId);
            datasetMapper.insert(dataset);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("datasetId", dataset.getId());
            payload.put("tempPath", tempFile.toString());
            payload.put("fileType", fileType);
            payload.put("hasHeader", uploadDTO.getHasHeader() == null || uploadDTO.getHasHeader());
            payload.put("encoding", uploadDTO.getEncoding());
            AsyncJob job = jobService.create(JobService.TYPE_IMPORT, dataset, null, null, payload);
            metrics.uploadSuccess();
            log.info("异步导入已提交: datasetId={}, jobId={}", dataset.getId(), job.getId());
            return new AsyncImportVO(job.getId(), dataset.getId(), true);
        } catch (BusinessException e) {
            rollbackAsyncSubmit(dataset, tempFile);
            throw e;
        } catch (IOException e) {
            rollbackAsyncSubmit(dataset, tempFile);
            throw new BusinessException(400, "上传文件保存失败，请重试");
        } catch (RuntimeException e) {
            rollbackAsyncSubmit(dataset, tempFile);
            throw e;
        }
    }

    private void rollbackAsyncSubmit(Dataset dataset, Path tempFile) {
        if (dataset != null && dataset.getId() != null) {
            try {
                datasetMapper.deleteById(dataset.getId());
            } catch (RuntimeException e) {
                log.warn("回滚异步导入占位数据集失败: {}", e.getClass().getSimpleName());
            }
        }
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("回滚异步导入临时文件失败: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 演示数据：仅 public + 未存在时创建；owner_type='demo'，anon_session_id 为空。
     */
    @Transactional(rollbackFor = Exception.class)
    public Dataset createDemoDataset(String name, String description, List<String> headers,
                                     List<Map<String, Object>> data) {
        Dataset existing = datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, name)
                .eq(Dataset::getOwnerType, OwnershipService.OWNER_DEMO)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        List<String> safeHeaders = headers.isEmpty() ? headers
                : headers.stream()
                .map(FileParserUtil::sanitizeColumnName)
                .collect(Collectors.toList());
        String tableName = SqlGuard.requireTableName("demo_sales_sample");
        return persistDataset(name, description, "demo_sales_sample.csv", 0L, "csv",
                tableName, safeHeaders, data, OwnershipService.OWNER_DEMO, null);
    }

    private Dataset persistDataset(String name, String description, String fileName, long fileSize,
                                   String fileType, String tableName, List<String> headers,
                                   List<Map<String, Object>> data, String ownerType, Long anonSessionId) {
        // 保存数据集元信息
        Dataset dataset = new Dataset();
        dataset.setName(name);
        dataset.setDescription(description);
        dataset.setFileName(fileName);
        dataset.setFileSize(fileSize);
        dataset.setFileType(fileType);
        dataset.setTableName(tableName);
        dataset.setRowCount(data.size());
        dataset.setColumnCount(headers.size());
        dataset.setStatus(1);
        dataset.setOwnerType(ownerType);
        dataset.setAnonSessionId(anonSessionId);
        datasetMapper.insert(dataset);

        // 推断列类型并保存列信息
        List<DatasetColumn> columns = inferAndSaveColumns(dataset.getId(), headers, data);

        // 创建数据表
        dynamicTableService.createTable(tableName, columns);

        // 插入数据
        if (!data.isEmpty()) {
            dynamicTableService.insertData(tableName, columns, data);
        }

        log.info("数据集创建成功: {}, 表名: {}, 行数: {}, 列数: {}, 归属: {}",
                dataset.getName(), tableName, data.size(), headers.size(), ownerType);

        return dataset;
    }

    /**
     * 预览数据
     */
    public DatasetPreviewVO previewData(Long datasetId, Integer page, Integer size) {
        Dataset dataset = getById(datasetId);
        page = (page == null || page < 1) ? 1 : page;
        if (page > 1_000_000) {
            throw new BusinessException(400, "页码超过上限");
        }
        size = SqlGuard.clampLimit(size, 20, 200);
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

        // 查询数据（表名先经过白名单校验）
        String safeTable = SqlGuard.requireTableName(dataset.getTableName());
        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = datasetMapper.selectDynamicDataPage(safeTable, offset, size);
        Long total = datasetMapper.selectDynamicDataCount(safeTable);

        vo.setRows(rows);
        vo.setTotal(total);

        return vo;
    }

    /**
     * 获取列信息
     */
    public List<DatasetColumn> getColumns(Long datasetId) {
        // 先做归属校验，避免通过列信息探测其他会话的数据集
        ownershipService.requireDataset(datasetId);
        return datasetColumnMapper.selectByDatasetId(datasetId);
    }

    /**
     * 删除数据集（演示数据禁止删除）。
     *
     * <p>关联完整性策略（基于真实表结构落实）：
     * <ul>
     *   <li>随数据集删除：版本、版本规则快照、列信息、统计缓存、质量报表、清洗规则、
     *       保存的图表及其看板项、chart_view 配额记录、备份表登记；</li>
     *   <li>保留为历史/审计记录：清洗日志（clean_log）、异步任务（async_job）、
     *       导出记录与审计日志，不再被数据集查询引用，但不会影响任何列表接口；</li>
     *   <li>物理表 DDL 会隐式提交事务，因此放到事务提交后执行：元数据删除失败时整体回滚，
     *       不会出现“表已删、元数据还在”的不一致。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDataset(Long id) {
        Dataset dataset = ownershipService.requireDataset(id);
        ownershipService.requireWritable(dataset);

        // 存在进行中的任务时拒绝删除，避免任务在删除后继续写入版本/数据造成新的不一致
        Long activeJobs = asyncJobMapper.selectCount(new LambdaQueryWrapper<AsyncJob>()
                .eq(AsyncJob::getDatasetId, id)
                .in(AsyncJob::getStatus, JobStatus.PENDING, JobStatus.RUNNING, JobStatus.CANCEL_REQUESTED));
        if (activeJobs != null && activeJobs > 0) {
            throw new BusinessException(409, "数据集存在进行中的任务，请等待完成或取消后再删除");
        }

        // 删除数据表（表名白名单校验后再进入 DDL）
        String safeTable = SqlGuard.requireTableName(dataset.getTableName());
        LinkedHashSet<String> tablesToDrop = new LinkedHashSet<>();
        tablesToDrop.add(safeTable);

        // 删除版本表：仅删除由 dataset_version 元数据精确证明归属的表，避免留下孤儿表
        List<DatasetVersion> versions = datasetVersionMapper.selectList(
                new LambdaQueryWrapper<DatasetVersion>().eq(DatasetVersion::getDatasetId, id));
        List<Long> versionIds = new ArrayList<>();
        for (DatasetVersion version : versions) {
            versionIds.add(version.getId());
            if (version.getTableName() != null && !version.getTableName().equals(safeTable)) {
                tablesToDrop.add(version.getTableName());
            }
        }

        // 删除备份表：先取全部表名，再在内存中按前缀过滤，避免 LIKE 拼接
        try {
            List<Map<String, Object>> tables = datasetMapper.selectDynamicSql("SHOW TABLES");
            String backupPrefix = safeTable + "_backup_";
            for (Map<String, Object> table : tables) {
                String tableName = table.values().iterator().next().toString();
                if (tableName.startsWith(backupPrefix)) {
                    tablesToDrop.add(tableName);
                }
            }
        } catch (Exception e) {
            log.warn("扫描备份表失败: {}", e.getClass().getSimpleName());
        }

        // 1) 版本规则快照：随版本元数据删除，避免快照失去归属
        if (!versionIds.isEmpty()) {
            datasetVersionRuleMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.DatasetVersionRule>()
                    .in(com.dataclean.entity.DatasetVersionRule::getVersionId, versionIds));
        }

        // 2) 质量报表（派生缓存，随数据集删除；旧版本报表不再有意义）
        dataQualityReportMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.DataQualityReport>()
                .eq(com.dataclean.entity.DataQualityReport::getDatasetId, id));

        // 3) 清洗规则（数据集级配置；清洗日志作为审计记录保留）
        cleanRuleMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.CleanRule>()
                .eq(com.dataclean.entity.CleanRule::getDatasetId, id));

        // 4) 保存的图表与看板项：先删看板项，再删图表，避免看板装配到孤儿图表
        List<SavedChart> charts = savedChartMapper.selectList(new LambdaQueryWrapper<SavedChart>()
                .eq(SavedChart::getDatasetId, id));
        if (!charts.isEmpty()) {
            List<Long> chartIds = charts.stream().map(SavedChart::getId).toList();
            dashboardItemMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.DashboardItem>()
                    .in(com.dataclean.entity.DashboardItem::getSavedChartId, chartIds));
            savedChartMapper.delete(new LambdaQueryWrapper<SavedChart>()
                    .in(SavedChart::getId, chartIds));
        }

        // 5) chart_view 配额记录
        chartViewMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.ChartView>()
                .eq(com.dataclean.entity.ChartView::getDatasetId, id));

        // 6) 版本、统计缓存、备份表登记、列信息、数据集主体
        datasetVersionMapper.delete(new LambdaQueryWrapper<DatasetVersion>()
                .eq(DatasetVersion::getDatasetId, id));
        statsCacheMapper.deleteByDataset(id);
        backupTableRecordMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.BackupTableRecord>()
                .eq(com.dataclean.entity.BackupTableRecord::getDatasetId, id));
        datasetColumnMapper.delete(new LambdaQueryWrapper<DatasetColumn>()
                .eq(DatasetColumn::getDatasetId, id));
        datasetMapper.deleteById(id);

        // 物理表在事务提交后删除（DDL 隐式提交，不能与元数据事务混用）
        dropTablesAfterCommit(tablesToDrop);

        log.info("数据集已删除: {}, 关联图表 {} 个, 物理表 {} 张", dataset.getName(), charts.size(), tablesToDrop.size());
    }

    /**
     * 事务提交后再删除物理表：提交前失败可完整回滚元数据；提交后失败仅留下孤儿表（可统计、可人工处理）。
     */
    private void dropTablesAfterCommit(Collection<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        Runnable drop = () -> {
            for (String table : tables) {
                try {
                    dynamicTableService.dropTable(SqlGuard.requireTableName(table));
                } catch (RuntimeException e) {
                    log.warn("删除物理表失败（将计入孤儿表统计）: {}, error={}",
                            table, e.getClass().getSimpleName());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    drop.run();
                }
            });
        } else {
            drop.run();
        }
    }

    /**
     * 数据集统计摘要（供 AI 分析复用）：统一走聚合下推 + 版本化缓存。
     */
    public Map<String, Object> getDatasetStatistics(Long datasetId) {
        return analysisService.getStatistics(datasetId);
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
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        // 表名最终仍走白名单校验，确保进入 DDL 的标识符受控
        return SqlGuard.requireTableName("data_" + base + "_" + System.currentTimeMillis());
    }
}
