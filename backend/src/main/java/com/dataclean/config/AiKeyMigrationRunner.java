package com.dataclean.config;

import com.dataclean.entity.AiConfig;
import com.dataclean.mapper.AiConfigMapper;
import com.dataclean.util.AiSecretCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量明文 API Key 迁移：启动时将 ai_config.api_key 转为密文并清空明文字段。
 *
 * <p>幂等：只处理 api_key 非空且 api_key_cipher 为空的行；重复执行不会重复加密。
 * 日志只输出条数，绝不输出密钥内容。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiKeyMigrationRunner implements ApplicationRunner {

    private final AiConfigMapper aiConfigMapper;
    private final AiSecretCipher aiSecretCipher;

    @Override
    public void run(ApplicationArguments args) {
        List<AiConfig> legacyConfigs;
        try {
            legacyConfigs = aiConfigMapper.selectLegacyPlaintextConfigs();
        } catch (Exception e) {
            // 迁移脚本尚未执行（缺少新列）时不影响应用启动
            log.warn("AI 密钥迁移跳过：无法读取待迁移配置（{}）", e.getClass().getSimpleName());
            return;
        }
        if (legacyConfigs == null || legacyConfigs.isEmpty()) {
            return;
        }
        if (!aiSecretCipher.isConfigured()) {
            log.warn("检测到 {} 条明文 API Key，但未配置 AI_SECRET_KEY，已跳过迁移", legacyConfigs.size());
            return;
        }

        int migrated = 0;
        for (AiConfig config : legacyConfigs) {
            try {
                String plain = config.getApiKey();
                if (plain == null || plain.isBlank()) {
                    continue;
                }
                AiConfig update = new AiConfig();
                update.setId(config.getId());
                update.setApiKeyCipher(aiSecretCipher.encrypt(plain));
                update.setApiKeyMask(AiSecretCipher.mask(plain));
                // 显式 SQL 清空明文，避免 MyBatis-Plus 忽略 null 字段
                aiConfigMapper.updateById(update);
                aiConfigMapper.clearPlainApiKey(config.getId());
                migrated++;
            } catch (Exception e) {
                log.warn("AI 密钥迁移失败：configId={}，原因={}", config.getId(), e.getClass().getSimpleName());
            }
        }
        log.info("AI 密钥迁移完成：共 {} 条，成功 {} 条", legacyConfigs.size(), migrated);

        // 兜底：即使个别行“写密文成功、清明文失败”，也在本次启动内统一清空残留明文
        try {
            int residual = aiConfigMapper.clearMigratedPlainKeys();
            if (residual > 0) {
                log.info("已清理 {} 条迁移后残留的明文密钥", residual);
            }
        } catch (Exception e) {
            log.warn("清理残留明文密钥失败：{}", e.getClass().getSimpleName());
        }
    }
}
