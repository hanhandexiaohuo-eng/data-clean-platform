package com.dataclean.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.dataclean.entity.AiConfig;
import com.dataclean.mapper.AiConfigMapper;
import com.dataclean.util.AiSecretCipher;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 H2 内存库（MySQL 兼容模式）验证存量明文 API Key 的迁移行为：
 * 迁移后明文清空、密文可解密、重复执行幂等、未配置主密钥时不破坏原数据。
 */
class AiKeyMigrationRunnerTest {

    private static final String PLAIN_KEY = "unit-test-placeholder-key-abcd";

    private SqlSessionFactory sqlSessionFactory;
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:ai_key_migration;MODE=MySQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE" +
                        ";DB_CLOSE_ON_EXIT=FALSE",
                "sa", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ai_config");
            statement.execute("CREATE TABLE ai_config (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100), api_url VARCHAR(500), api_key VARCHAR(500), " +
                    "api_key_cipher VARCHAR(1024), api_key_mask VARCHAR(64), " +
                    "model_name VARCHAR(100), temperature DECIMAL(3,2), max_tokens INT, " +
                    "timeout INT, is_default INT, status INT, " +
                    "created_at TIMESTAMP, updated_at TIMESTAMP)");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiConfigMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setDbConfig(new GlobalConfig.DbConfig());
        factoryBean.setGlobalConfig(globalConfig);
        sqlSessionFactory = factoryBean.getObject();
    }

    @Test
    void migratesLegacyPlaintextKeyAndClearsPlaintext() throws Exception {
        AiSecretCipher cipher = new AiSecretCipher(testMasterKey());
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiConfigMapper mapper = session.getMapper(AiConfigMapper.class);
            Long id = insertLegacyConfig(mapper);

            new AiKeyMigrationRunner(mapper, cipher).run(null);

            AiConfig migrated = mapper.selectById(id);
            assertNull(migrated.getApiKey(), "迁移后明文字段必须被清空");
            assertNotNull(migrated.getApiKeyCipher(), "必须写入密文");
            assertTrue(migrated.getApiKeyCipher().startsWith("v1:"), "密文需带版本前缀");
            assertNotNull(migrated.getApiKeyMask(), "必须写入掩码");
            assertTrue(migrated.getApiKeyMask().endsWith("abcd"));
            assertFalse(migrated.getApiKeyMask().contains(PLAIN_KEY));
            assertEquals(PLAIN_KEY, cipher.decrypt(migrated.getApiKeyCipher()), "密文应可解密回原值");

            // 幂等：再次执行不应改变密文
            String firstCipher = migrated.getApiKeyCipher();
            new AiKeyMigrationRunner(mapper, cipher).run(null);
            assertEquals(firstCipher, mapper.selectById(id).getApiKeyCipher());
        }
    }

    @Test
    void keepsPlaintextWhenMasterKeyMissing() throws Exception {
        AiSecretCipher unconfigured = new AiSecretCipher("");
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            AiConfigMapper mapper = session.getMapper(AiConfigMapper.class);
            Long id = insertLegacyConfig(mapper);

            new AiKeyMigrationRunner(mapper, unconfigured).run(null);

            AiConfig after = mapper.selectById(id);
            assertEquals(PLAIN_KEY, after.getApiKey(), "未配置主密钥时不得删除或破坏原明文");
            assertNull(after.getApiKeyCipher());
        }
    }

    private static Long insertLegacyConfig(AiConfigMapper mapper) {
        AiConfig config = new AiConfig();
        config.setName("默认配置");
        config.setApiUrl("https://example.com/v1/chat");
        config.setApiKey(PLAIN_KEY);
        config.setModelName("test-model");
        config.setStatus(1);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        mapper.insert(config);
        return config.getId();
    }

    private static String testMasterKey() {
        // 仅测试用固定主密钥（全 0 字节），不是任何真实环境的密钥
        return Base64.getEncoder().encodeToString(new byte[32]);
    }
}
