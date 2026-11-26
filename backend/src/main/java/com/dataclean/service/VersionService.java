package com.dataclean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataclean.config.JobProperties;
import com.dataclean.entity.Dataset;
import com.dataclean.entity.DatasetColumn;
import com.dataclean.entity.DatasetVersion;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.DatasetVersionMapper;
import com.dataclean.mapper.DatasetVersionRuleMapper;
import com.dataclean.mapper.StatsCacheMapper;
import com.dataclean.util.SqlGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据集版本管理。
 *
 * <p>核心不变量：
 * 1. 每个版本对应独立物理表；
 * 2. `dataset.current_version_id` 指向当前可用版本；
 * 3. 清洗/恢复只新增或切换版本，从不原地覆盖；
 * 4. 版本切换时同步刷新列元数据并清理统计缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionService {

    private final DatasetMapper datasetMapper;
    private final DatasetVersionMapper versionMapper;
    private final DatasetVersionRuleMapper versionRuleMapper;
    private final DatasetColumnMapper datasetColumnMapper;
    private final StatsCacheMapper statsCacheMapper;
    private final JobProperties jobProperties;

    /** 为存量数据集补建初始版本记录（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public DatasetVersion ensureInitialVersion(Dataset dataset) {
        return ensureInitialVersion(dataset, DatasetVersion.SOURCE_BACKFILL);
    }

    /** 为数据集补建初始版本记录，source 用于区分同步导入与存量回填。 */
    @Transactional(rollbackFor = Exception.class)
    public DatasetVersion ensureInitialVersion(Dataset dataset, String source) {
        if (dataset.getCurrentVersionId() != null) {
            return versionMapper.selectById(dataset.getCurrentVersionId());
        }
        DatasetVersion existing = versionMapper.selectOne(new LambdaQueryWrapper<DatasetVersion>()
                .eq(DatasetVersion::getDatasetId, dataset.getId())
                .orderByAsc(DatasetVersion::getVersionNo)
                .last("LIMIT 1"));
        if (existing != null) {
            dataset.setCurrentVersionId(existing.getId());
            datasetMapper.updateById(dataset);
            return existing;
        }
        DatasetVersion version = new DatasetVersion();
        version.setDatasetId(dataset.getId());
        version.setVersionNo(1);
        version.setTableName(dataset.getTableName());
        version.setRowCount(dataset.getRowCount() == null ? 0L : dataset.getRowCount().longValue());
        version.setColumnCount(dataset.getColumnCount() == null ? 0 : dataset.getColumnCount());
        version.setChecksum(checksum(dataset.getTableName()));
        version.setStatus(DatasetVersion.STATUS_ACTIVE);
        version.setSource(source == null ? DatasetVersion.SOURCE_BACKFILL : source);
        version.setOwnerType(dataset.getOwnerType());
        version.setAnonSessionId(dataset.getAnonSessionId());
        version.setActivatedAt(LocalDateTime.now());
        versionMapper.insert(version);

        dataset.setCurrentVersionId(version.getId());
        datasetMapper.updateById(dataset);
        return version;
    }

    public DatasetVersion createBuildingVersion(Dataset dataset, String source, Long jobId) {
        int nextNo = versionMapper.maxVersionNo(dataset.getId()) + 1;
        String tableName = versionTableName(dataset.getTableName(), nextNo);
        DatasetVersion version = new DatasetVersion();
        version.setDatasetId(dataset.getId());
        version.setVersionNo(nextNo);
        version.setTableName(tableName);
        version.setRowCount(0L);
        version.setColumnCount(0);
        version.setStatus(DatasetVersion.STATUS_BUILDING);
        version.setSource(source);
        version.setJobId(jobId);
        version.setOwnerType(dataset.getOwnerType());
        version.setAnonSessionId(dataset.getAnonSessionId());
        versionMapper.insert(version);
        return version;
    }

    /** 版本表名：保留原表名（<=48 字符）并追加 _v{版本号}，总长不超过 64。 */
    public String versionTableName(String baseTable, int versionNo) {
        String base = SqlGuard.requireTableName(baseTable);
        String suffix = "_v" + versionNo;
        if (base.length() + suffix.length() > 64) {
            base = base.substring(0, 64 - suffix.length());
        }
        return SqlGuard.requireTableName(base + suffix);
    }

    @Transactional(rollbackFor = Exception.class)
    public void activate(Dataset dataset, DatasetVersion version) {
        versionMapper.archiveActive(dataset.getId());
        versionMapper.activate(version.getId());
        version.setStatus(DatasetVersion.STATUS_ACTIVE);

        dataset.setTableName(version.getTableName());
        dataset.setRowCount(version.getRowCount() == null ? 0 : version.getRowCount().intValue());
        dataset.setColumnCount(version.getColumnCount() == null ? 0 : version.getColumnCount());
        dataset.setCurrentVersionId(version.getId());
        datasetMapper.updateById(dataset);

        refreshColumnMetadata(dataset, version.getTableName());
        statsCacheMapper.deleteByDataset(dataset.getId());
    }

    /** 版本切换后重建列元数据，保证后续清洗/统计使用当前版本的真实结构。 */
    public void refreshColumnMetadata(Dataset dataset, String tableName) {
        List<Map<String, Object>> rows = datasetMapper.selectDynamicSqlWithParams(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, ORDINAL_POSITION, COLUMN_TYPE " +
                        "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() " +
                        "AND TABLE_NAME = #{params[0]} ORDER BY ORDINAL_POSITION",
                Collections.singletonList(tableName));

        datasetColumnMapper.delete(new LambdaQueryWrapper<DatasetColumn>()
                .eq(DatasetColumn::getDatasetId, dataset.getId()));

        int order = 0;
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("COLUMN_NAME"));
            if ("_id".equals(name)) {
                continue;
            }
            DatasetColumn column = new DatasetColumn();
            column.setDatasetId(dataset.getId());
            column.setColumnName(name);
            column.setColumnAlias(name);
            column.setDataType(String.valueOf(row.get("DATA_TYPE")).toUpperCase(Locale.ROOT));
            column.setJavaType("string");
            column.setSortOrder(order++);
            column.setNullable("YES".equalsIgnoreCase(String.valueOf(row.get("IS_NULLABLE"))) ? 1 : 0);
            datasetColumnMapper.insert(column);
        }
        dataset.setColumnCount(order);
        datasetMapper.updateById(dataset);
    }

    public List<DatasetVersion> listVersions(Long datasetId) {
        return versionMapper.listByDataset(datasetId);
    }

    public DatasetVersion requireVersion(Long datasetId, Long versionId) {
        DatasetVersion version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null || !datasetId.equals(version.getDatasetId())) {
            throw new BusinessException(404, "数据版本不存在");
        }
        return version;
    }

    public boolean tableExists(String tableName) {
        String safe = SqlGuard.requireTableName(tableName);
        List<Map<String, Object>> rows = datasetMapper.selectDynamicSqlWithParams(
                "SELECT COUNT(*) AS c FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = #{params[0]}",
                Collections.singletonList(safe));
        return !rows.isEmpty() && ((Number) rows.get(0).get("c")).longValue() > 0;
    }

    /**
     * 数据校验摘要：行数 + MySQL 表校验和。
     * 用于版本恢复前验证备份数据完整可用。
     */
    public String checksum(String tableName) {
        String safe = SqlGuard.requireTableName(tableName);
        try {
            Long rows = datasetMapper.selectDynamicDataCount(safe);
            List<Map<String, Object>> result = datasetMapper.selectDynamicSql("CHECKSUM TABLE `" + safe + "`");
            Object value = result.isEmpty() ? null : result.get(0).get("Checksum");
            long checksum = value == null ? 0L : ((Number) value).longValue();
            return rows + ":" + checksum;
        } catch (RuntimeException e) {
            log.warn("计算校验摘要失败: type={}", e.getClass().getSimpleName());
            return "0:0";
        }
    }

    public void dropVersionTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return;
        }
        try {
            datasetMapper.executeDynamicSql("DROP TABLE IF EXISTS `" + SqlGuard.requireTableName(tableName) + "`");
        } catch (RuntimeException e) {
            log.warn("删除版本表失败: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 版本保留策略：保留 ACTIVE 与最近 N 个版本；超出数量且超过最短保留天数的 ARCHIVED 版本才会被回收。
     * 任何情况下至少保留一个可恢复版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public int pruneVersions(Dataset dataset) {
        List<DatasetVersion> versions = versionMapper.listByDataset(dataset.getId());
        if (versions.size() <= Math.max(2, jobProperties.getVersionKeep())) {
            return 0;
        }
        LocalDateTime threshold = LocalDateTime.now().minusDays(jobProperties.getVersionMaxAgeDays());
        int removed = 0;
        int index = 0;
        for (DatasetVersion version : versions) {
            index++;
            if (DatasetVersion.STATUS_ACTIVE.equals(version.getStatus())) {
                continue;
            }
            if (index <= jobProperties.getVersionKeep()) {
                continue;
            }
            if (version.getCreatedAt() != null && version.getCreatedAt().isAfter(threshold)) {
                continue;
            }
            dropVersionTable(version.getTableName());
            deleteVersionRules(version.getId());
            versionMapper.deleteById(version.getId());
            removed++;
        }
        return removed;
    }

    /**
     * 手动删除历史版本（破坏性操作，调用方必须先二次确认并审计）。
     * 当前版本、构建中的版本不允许删除；且至少保留一个可恢复的历史版本。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(Dataset dataset, Long versionId) {
        DatasetVersion version = requireVersion(dataset.getId(), versionId);
        if (DatasetVersion.STATUS_ACTIVE.equals(version.getStatus())
                || DatasetVersion.STATUS_BUILDING.equals(version.getStatus())) {
            throw new BusinessException(400, "当前版本或构建中的版本不能删除");
        }
        List<DatasetVersion> versions = versionMapper.listByDataset(dataset.getId());
        if (versions.size() <= 2) {
            throw new BusinessException(400, "至少保留一个可恢复的历史版本");
        }
        dropVersionTable(version.getTableName());
        deleteVersionRules(version.getId());
        versionMapper.deleteById(version.getId());
    }

    /** 版本删除/回收时同步清理规则快照，避免孤儿规则记录。 */
    public void deleteVersionRules(Long versionId) {
        try {
            versionRuleMapper.delete(new LambdaQueryWrapper<com.dataclean.entity.DatasetVersionRule>()
                    .eq(com.dataclean.entity.DatasetVersionRule::getVersionId, versionId));
        } catch (RuntimeException e) {
            log.warn("删除版本规则快照失败: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * 私有数据版本保留：每日夜间按“保留数量 + 最短保留天数”回收过旧的历史版本。
     * 只处理 owner_type=private 的数据集，绝不触碰演示数据与匿名会话数据。
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 3 * * *")
    public void scheduledPrunePrivateVersions() {
        try {
            prunePrivateVersions();
        } catch (RuntimeException e) {
            log.warn("私有版本保留任务异常: {}", e.getClass().getSimpleName());
        }
    }

    public int prunePrivateVersions() {
        List<Dataset> datasets = datasetMapper.selectList(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getOwnerType, OwnershipService.OWNER_PRIVATE));
        int removed = 0;
        for (Dataset dataset : datasets) {
            try {
                removed += pruneVersions(dataset);
            } catch (RuntimeException e) {
                log.warn("版本保留失败: datasetId={}, error={}", dataset.getId(), e.getClass().getSimpleName());
            }
        }
        return removed;
    }

    /** 只上报无法证明归属的动态表，不执行删除。 */
    public List<String> orphanTables() {
        List<Map<String, Object>> rows = datasetMapper.selectDynamicSql(
                "SELECT t.TABLE_NAME FROM INFORMATION_SCHEMA.TABLES t " +
                        "WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_NAME LIKE 'data!_%' ESCAPE '!' " +
                        "AND NOT EXISTS (SELECT 1 FROM dataset d WHERE d.table_name = t.TABLE_NAME) " +
                        "AND NOT EXISTS (SELECT 1 FROM dataset_version v WHERE v.table_name = t.TABLE_NAME) " +
                        "AND NOT EXISTS (SELECT 1 FROM backup_table b WHERE b.table_name = t.TABLE_NAME) " +
                        "AND NOT EXISTS (SELECT 1 FROM clean_log l WHERE l.backup_table = t.TABLE_NAME)");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            names.add(String.valueOf(row.get("TABLE_NAME")));
        }
        return names;
    }
}
