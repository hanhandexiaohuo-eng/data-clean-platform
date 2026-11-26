package com.dataclean.mapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 H2 内存库（MySQL 兼容模式）验证动态 SQL 的参数绑定确实生效，
 * 且过滤值无法改变 SQL 语义。此测试不连接任何真实数据库。
 */
class DatasetMapperBindingTest {

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:ds_binding;MODE=MySQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(DatasetMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

        execute(dataSource, "CREATE TABLE test_data (id INT AUTO_INCREMENT PRIMARY KEY, " +
                "name VARCHAR(50), amt DECIMAL(10,2))");
        execute(dataSource, "INSERT INTO test_data(name, amt) VALUES ('Alice', 10.50), ('Bob', 20.00)");
    }

    @AfterAll
    static void tearDown() {
        sqlSessionFactory = null;
    }

    @Test
    void dynamicSqlBindsFilterValueWithoutChangingSemantics() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            DatasetMapper mapper = session.getMapper(DatasetMapper.class);

            // 正常过滤
            List<Map<String, Object>> rows = mapper.selectDynamicSqlWithParams(
                    "SELECT `name`, `amt` FROM `test_data` WHERE `name` = #{params[0]} LIMIT #{params[1]}",
                    List.of("Bob", 10));
            assertEquals(1, rows.size());
            assertEquals("Bob", valueOf(rows.get(0), "name"));

            // 注入型取值只会被当作普通字符串比较，不会返回全表
            List<Map<String, Object>> injected = mapper.selectDynamicSqlWithParams(
                    "SELECT `name` FROM `test_data` WHERE `name` = #{params[0]} LIMIT 10",
                    List.of("Alice' OR '1'='1"));
            assertTrue(injected.isEmpty(), "过滤值必须按参数绑定处理，不能被解释为 SQL 片段");

            // 含分号、注释符号的取值同样不生效
            List<Map<String, Object>> comment = mapper.selectDynamicSqlWithParams(
                    "SELECT `name` FROM `test_data` WHERE `name` = #{params[0]} LIMIT 10",
                    List.of("Bob' -- "));
            assertTrue(comment.isEmpty());
        }
    }

    @Test
    void dynamicUpdateBindsValuesAndTableStillExists() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            DatasetMapper mapper = session.getMapper(DatasetMapper.class);

            mapper.executeDynamicSqlWithParams(
                    "UPDATE `test_data` SET `amt` = #{params[0]} WHERE `name` = #{params[1]}",
                    List.of(new BigDecimal("99.00"), "Alice"));

            List<Map<String, Object>> rows = mapper.selectDynamicSqlWithParams(
                    "SELECT `amt` FROM `test_data` WHERE `name` = #{params[0]}", List.of("Alice"));
            assertEquals(1, rows.size());
            assertNotNull(valueOf(rows.get(0), "amt"));

            // 恶意取值不会造成额外语句执行，表仍然存在
            mapper.executeDynamicSqlWithParams(
                    "UPDATE `test_data` SET `amt` = #{params[0]} WHERE `name` = #{params[1]}",
                    List.of(new BigDecimal("1.00"), "Alice'; DROP TABLE test_data; -- "));
            List<Map<String, Object>> all = mapper.selectDynamicSqlWithParams(
                    "SELECT `name` FROM `test_data` LIMIT 10", List.of());
            assertEquals(2, all.size());
        }
    }

    private static Object valueOf(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void execute(DataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
