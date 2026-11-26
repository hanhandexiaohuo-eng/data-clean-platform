package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.entity.Dataset;
import com.dataclean.exception.BusinessException;
import com.dataclean.mapper.CleanLogMapper;
import com.dataclean.mapper.CleanRuleMapper;
import com.dataclean.mapper.DatasetMapper;
import com.dataclean.util.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 公开模式可见性：demo + 本会话；其他会话/私有统一 404。
 */
@ExtendWith(MockitoExtension.class)
class OwnershipServicePublicVisibilityTest {

    @Mock
    private DatasetMapper datasetMapper;
    @Mock
    private CleanRuleMapper cleanRuleMapper;
    @Mock
    private CleanLogMapper cleanLogMapper;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private Dataset dataset(long id, String ownerType, Long sessionId) {
        Dataset dataset = new Dataset();
        dataset.setId(id);
        dataset.setOwnerType(ownerType);
        dataset.setAnonSessionId(sessionId);
        dataset.setTableName("ds_" + id);
        return dataset;
    }

    @Test
    void publicMode_demoVisible_otherSessionHidden_privateHidden() {
        RequestContext.set(7L, "ip-hash", true);
        OwnershipService service = new OwnershipService(
                new AppMode("public"), datasetMapper, cleanRuleMapper, cleanLogMapper);

        when(datasetMapper.selectById(1L)).thenReturn(dataset(1L, "demo", null));
        when(datasetMapper.selectById(2L)).thenReturn(dataset(2L, "anon", 7L));
        when(datasetMapper.selectById(3L)).thenReturn(dataset(3L, "anon", 8L));
        when(datasetMapper.selectById(4L)).thenReturn(dataset(4L, "private", null));

        assertEquals(1L, service.requireDataset(1L).getId());
        assertEquals(2L, service.requireDataset(2L).getId());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireDataset(3L)).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireDataset(4L)).getCode());
    }

    @Test
    void demoDataset_isNotWritable_inPublicMode() {
        RequestContext.set(7L, "ip-hash", true);
        OwnershipService service = new OwnershipService(
                new AppMode("public"), datasetMapper, cleanRuleMapper, cleanLogMapper);

        Dataset demo = dataset(1L, "demo", null);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.requireWritable(demo)).getCode());
    }

    @Test
    void privateMode_keepsAllDatasetsVisible() {
        RequestContext.set(null, null, false);
        OwnershipService service = new OwnershipService(
                new AppMode("private"), datasetMapper, cleanRuleMapper, cleanLogMapper);

        when(datasetMapper.selectById(9L)).thenReturn(dataset(9L, "private", null));
        assertEquals(9L, service.requireDataset(9L).getId());
    }

    @Test
    void missingDataset_returns404() {
        OwnershipService service = new OwnershipService(
                new AppMode("private"), datasetMapper, cleanRuleMapper, cleanLogMapper);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireDataset(999L)).getCode());
    }
}
