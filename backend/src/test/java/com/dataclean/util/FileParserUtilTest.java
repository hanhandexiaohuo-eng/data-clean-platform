package com.dataclean.util;

import com.dataclean.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class FileParserUtilTest {

    @Test
    void parsesValidCsv() throws Exception {
        String csv = "name,age\nzhangsan,20\nlisi,30\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        FileParserUtil.ParseResult result = FileParserUtil.parseFile(file, true, "UTF-8");

        assertEquals(2, result.headers().size());
        assertEquals("name", result.headers().get(0));
        assertEquals(2, result.rowCount());
    }

    @Test
    void rejectsCsvWithBinaryContent() {
        byte[] zipHeader = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.csv", "text/csv", zipHeader);

        BusinessException e = assertThrows(BusinessException.class,
                () -> FileParserUtil.detectFileType(file));
        assertEquals(400, e.getCode());
    }

    @Test
    void rejectsXlsxWithoutZipHeader() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.xlsx", "application/vnd.ms-excel", "not an excel".getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> FileParserUtil.detectFileType(file));
    }

    @Test
    void rejectsCsvContainingNulBytes() {
        byte[] content = {'a', ',', 'b', '\n', 0x00, 0x01, 0x02};
        MockMultipartFile file = new MockMultipartFile("file", "bad.csv", "text/csv", content);

        assertThrows(BusinessException.class, () -> FileParserUtil.detectFileType(file));
    }

    @Test
    void rejectsUnknownExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.exe", "application/octet-stream", new byte[]{1, 2, 3});
        assertThrows(BusinessException.class, () -> FileParserUtil.detectFileType(file));
    }

    @Test
    void rejectsTooManyColumns() {
        String header = IntStream.rangeClosed(1, FileParserUtil.MAX_COLUMNS + 1)
                .mapToObj(i -> "c" + i)
                .collect(Collectors.joining(","));
        MockMultipartFile file = new MockMultipartFile(
                "file", "wide.csv", "text/csv", (header + "\n").getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> FileParserUtil.parseFile(file, true, "UTF-8"));
    }

    @Test
    void rejectsUnsupportedEncoding() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.csv", "text/csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(BusinessException.class, () -> FileParserUtil.parseFile(file, true, "UTF-32"));
    }

    @Test
    void sanitizeFileName_stripsPathTraversal() {
        assertEquals("passwd.csv", FileParserUtil.sanitizeFileName("../../etc/passwd.csv"));
        assertEquals("evil.csv", FileParserUtil.sanitizeFileName("C:\\temp\\evil.csv"));
        assertEquals("upload", FileParserUtil.sanitizeFileName(".."));
        assertFalse(FileParserUtil.sanitizeFileName("../../x.csv").contains(".."));
    }

    @Test
    void escapeCsvFormula_neutralizesDangerousPrefix() {
        assertEquals("'=1+1", FileParserUtil.escapeCsvFormula("=1+1"));
        assertEquals("'+SUM(A1)", FileParserUtil.escapeCsvFormula("+SUM(A1)"));
        assertEquals("'@cmd", FileParserUtil.escapeCsvFormula("@cmd"));
        assertEquals("normal", FileParserUtil.escapeCsvFormula("normal"));
        assertEquals("'-123", FileParserUtil.escapeCsvFormula("-123"));
    }

    @Test
    void deduplicateHeaders_appendsSuffix() {
        var headers = FileParserUtil.deduplicateHeaders(java.util.List.of("a", "a", "a", "b"));
        assertEquals(java.util.List.of("a", "a_2", "a_3", "b"), headers);
    }
}
