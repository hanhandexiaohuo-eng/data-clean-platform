package com.dataclean.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV 导出单元格：公式注入防护与 RFC4180 转义。
 */
class ExportCsvGuardTest {

    private ExportService exportService;
    private Method csvCell;

    @BeforeEach
    void setUp() throws Exception {
        exportService = new ExportService(null, null, null, null, null, null);
        csvCell = ExportService.class.getDeclaredMethod("csvCell", String.class);
        csvCell.setAccessible(true);
    }

    private String cell(String raw) throws Exception {
        return (String) csvCell.invoke(exportService, raw);
    }

    @Test
    void prefixesDangerousLeadingCharacters() throws Exception {
        assertEquals("'=1+1", cell("=1+1"));
        assertEquals("'+1", cell("+1"));
        assertEquals("'-1", cell("-1"));
        assertEquals("'@SUM(A1)", cell("@SUM(A1)"));
        assertEquals("'\tvalue", cell("\tvalue"));
    }

    @Test
    void keepsPlainTextUntouched() throws Exception {
        assertEquals("hello", cell("hello"));
        assertEquals("", cell(""));
        assertEquals("a-b", cell("a-b"));
    }

    @Test
    void quotesWhenContainingSeparatorsOrQuotes() throws Exception {
        assertEquals("\"a,b\"", cell("a,b"));
        assertEquals("\"he said \"\"hi\"\"\"", cell("he said \"hi\""));
        assertEquals("\"line1\nline2\"", cell("line1\nline2"));
    }

    @Test
    void formulaAndSeparatorAreBothEscaped() throws Exception {
        assertEquals("\"'=a,b\"", cell("=a,b"));
    }
}
