package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.dto.CleanRuleDTO;
import com.dataclean.entity.CleanRule;
import com.dataclean.entity.Dataset;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.ChartViewMapper;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.CleanRuleMapper;
import com.dataclean.mapper.DatasetColumnMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.mapper.ExportRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CleanServiceRuleValidationTest {

    @Mock
    private CleanRuleMapper cleanRuleMapper;
    @Mock
    private CleanLogMapper cleanLogMapper;
    @Mock
    private DatasetMapper datasetMapper;
    @Mock
    private DatasetColumnMapper datasetColumnMapper;
    @Mock
    private DynamicTableService dynamicTableService;

    private CleanService cleanService;

    @BeforeEach
    void setUp() {
        AppMode appMode = new AppMode("private");
        OwnershipService ownershipService = new OwnershipService(
                appMode, datasetMapper, cleanRuleMapper, cleanLogMapper);
        PublicQuotaService quotaService = new PublicQuotaService(
                appMode, new PublicModeProperties(), datasetMapper, cleanLogMapper,
                org.mockito.Mockito.mock(ChartViewMapper.class),
                org.mockito.Mockito.mock(ExportRecordMapper.class));
        cleanService = new CleanService(cleanRuleMapper, cleanLogMapper, datasetMapper,
                datasetColumnMapper, dynamicTableService, new ObjectMapper(),
                ownershipService, quotaService, org.mockito.Mockito.mock(JobService.class));

        Dataset dataset = new Dataset();
        dataset.setId(1L);
        dataset.setTableName("ds_test");
        dataset.setOwnerType("private");
        when(datasetMapper.selectById(1L)).thenReturn(dataset);
    }

    private CleanRuleDTO baseRule(String ruleType) {
        CleanRuleDTO dto = new CleanRuleDTO();
        dto.setDatasetId(1L);
        dto.setRuleName("测试规则");
        dto.setRuleType(ruleType);
        dto.setSortOrder(1);
        dto.setStatus(1);
        return dto;
    }

    @Test
    void typeConvert_rejectsArbitraryDdl() {
        CleanRuleDTO dto = baseRule("type_convert");
        dto.setTargetColumn("age");
        dto.setTargetType("VARCHAR(255); DROP TABLE user");

        BusinessException e = assertThrows(BusinessException.class, () -> cleanService.createRule(dto));
        assertEquals(400, e.getCode());
        verify(cleanRuleMapper, never()).insert(any(CleanRule.class));
    }

    @Test
    void typeConvert_acceptsWhitelistedType() {
        CleanRuleDTO dto = baseRule("type_convert");
        dto.setTargetColumn("age");
        dto.setTargetType("decimal(20,6)");

        cleanService.createRule(dto);

        verify(cleanRuleMapper).insert(any(CleanRule.class));
    }

    @Test
    void missingValue_rejectsInvalidColumn() {
        CleanRuleDTO dto = baseRule("missing_value_handle");
        dto.setTargetColumn("age`; DROP TABLE user; --");
        dto.setHandleType("delete");

        assertEquals(400, assertThrows(BusinessException.class,
                () -> cleanService.createRule(dto)).getCode());
    }

    @Test
    void missingValue_rejectsUnknownHandleType() {
        CleanRuleDTO dto = baseRule("missing_value_handle");
        dto.setTargetColumn("age");
        dto.setHandleType("rm -rf");

        assertEquals(400, assertThrows(BusinessException.class,
                () -> cleanService.createRule(dto)).getCode());
    }

    @Test
    void customFilter_isOfflineByDefault() {
        CleanRuleDTO dto = baseRule("custom_filter");
        dto.setFilterExpression("age > 18 OR 1=1");

        assertEquals(400, assertThrows(BusinessException.class,
                () -> cleanService.createRule(dto)).getCode());
    }

    @Test
    void unknownRuleType_isRejected() {
        CleanRuleDTO dto = baseRule("drop_table");

        assertEquals(400, assertThrows(BusinessException.class,
                () -> cleanService.createRule(dto)).getCode());
    }

    @Test
    void columnStandardize_rejectsNonWhitelistedTypeFromMetadata() {
        CleanRule rule = new CleanRule();
        rule.setRuleType("column_standardize");
        rule.setRuleConfig("{}");
        when(datasetMapper.selectDynamicSqlWithParams(anyString(), anyList()))
                .thenReturn(java.util.List.of(java.util.Map.of(
                        "COLUMN_NAME", "Name", "COLUMN_TYPE", "VARCHAR(10); DROP TABLE x")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> cleanService.applyRule("ds_test", rule));

        assertEquals(400, e.getCode());
        verify(datasetMapper, never()).executeDynamicSql(anyString());
    }

    @Test
    void columnStandardize_reusesWhitelistedMetadataType() throws Exception {
        CleanRule rule = new CleanRule();
        rule.setRuleType("column_standardize");
        rule.setRuleConfig("{}");
        when(datasetMapper.selectDynamicSqlWithParams(anyString(), anyList()))
                .thenReturn(java.util.List.of(java.util.Map.of(
                        "COLUMN_NAME", "UserName", "COLUMN_TYPE", "varchar(500)")));

        cleanService.applyRule("ds_test", rule);

        // 类型必须来自 INFORMATION_SCHEMA 且原样回填，而不是任何子查询或外部参数
        verify(datasetMapper, times(1)).executeDynamicSql(
                "ALTER TABLE `ds_test` CHANGE `UserName` `username` varchar(500)");
    }
}
