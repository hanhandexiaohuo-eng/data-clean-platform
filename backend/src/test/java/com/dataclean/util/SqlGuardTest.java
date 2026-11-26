package com.dataclean.util;

import com.dataclean.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlGuardTest {

    @Test
    void identifier_acceptsNormalColumns() {
        assertEquals("city", SqlGuard.requireIdentifier("city", "列名"));
        assertEquals("销售额", SqlGuard.requireIdentifier("销售额", "列名"));
        assertEquals("col_1", SqlGuard.requireIdentifier("col_1", "列名"));
    }

    @Test
    void identifier_rejectsInjectionPayloads() {
        String[] bad = {
                "city`; DROP TABLE user; --",
                "city' OR '1'='1",
                "1abc",
                "a b",
                "a-b",
                "city/*x*/",
                "",
                null
        };
        for (String value : bad) {
            assertThrows(BusinessException.class,
                    () -> SqlGuard.requireIdentifier(value, "列名"), "应拒绝: " + value);
        }
    }

    @Test
    void tableName_rejectsSystemSchemas() {
        assertThrows(BusinessException.class, () -> SqlGuard.requireTableName("information_schema"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireTableName("mysql"));
        assertEquals("data_test_1", SqlGuard.requireTableName("data_test_1"));
    }

    @Test
    void aggregation_onlyWhitelisted() {
        assertEquals("SUM", SqlGuard.requireAggregation("sum", "COUNT"));
        assertEquals("COUNT", SqlGuard.requireAggregation(null, "COUNT"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireAggregation("SUM(x); DROP TABLE t", "COUNT"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireAggregation("COUNT(*)", "COUNT"));
    }

    @Test
    void chartType_onlyWhitelisted() {
        assertEquals("bar", SqlGuard.requireChartType("BAR"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireChartType("script"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireChartType(null));
    }

    @Test
    void filterOperator_onlyWhitelisted() {
        assertEquals("like", SqlGuard.requireFilterOperator("LIKE"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireFilterOperator("; DROP"));
    }

    @Test
    void columnType_onlyWhitelisted() {
        assertEquals("INT", SqlGuard.requireColumnType("int"));
        assertEquals("DECIMAL(20,6)", SqlGuard.requireColumnType("decimal(20,6)"));
        assertEquals("VARCHAR(255)", SqlGuard.requireColumnType("varchar(255)"));

        assertThrows(BusinessException.class, () -> SqlGuard.requireColumnType("VARCHAR(255); DROP TABLE t"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireColumnType("TEXT -- x"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireColumnType("DECIMAL(999,999)"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireColumnType("VARCHAR(99999)"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireColumnType("BLOB"));
    }

    @Test
    void limit_isBounded() {
        assertEquals(100, SqlGuard.clampLimit(null, 100, 5000));
        assertEquals(100, SqlGuard.clampLimit(0, 100, 5000));
        assertEquals(5000, SqlGuard.clampLimit(5000, 100, 5000));
        assertThrows(BusinessException.class, () -> SqlGuard.clampLimit(5001, 100, 5000));
        assertThrows(BusinessException.class, () -> SqlGuard.clampLimit(99999999, 100, 5000));
    }

    @Test
    void sortDirection_onlyAscDesc() {
        assertEquals("ASC", SqlGuard.requireSortDirection(null));
        assertEquals("DESC", SqlGuard.requireSortDirection("desc"));
        assertThrows(BusinessException.class, () -> SqlGuard.requireSortDirection("DESC; DROP"));
    }

    @Test
    void violation_usesHttp400Code() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> SqlGuard.requireIdentifier("x; DROP", "列名"));
        assertEquals(400, e.getCode());
    }
}
