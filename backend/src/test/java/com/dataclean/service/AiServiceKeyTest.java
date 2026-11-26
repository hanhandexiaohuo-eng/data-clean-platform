package com.dataclean.service;

import com.dataclean.dto.AiConfigDTO;
import com.dataclean.dto.AiConfigVO;
import com.dataclean.entity.AiConfig;
import com.dataclean.mapper.AiConfigMapper;
import com.dataclean.mapper.AiGenerationLogMapper;
import com.dataclean.mapper.AiPromptTemplateMapper;
import com.dataclean.util.AiPiiMasker;
import com.dataclean.util.AiSecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiServiceKeyTest {

    // 单元测试占位样本（非真实密钥，扫描脚本按 placeholder 白名单放行）
    private static final String RAW_KEY = "sk-test-placeholder-0000000000";

    @Mock
    private AiConfigMapper aiConfigMapper;
    @Mock
    private AiPromptTemplateMapper aiPromptTemplateMapper;
    @Mock
    private AiGenerationLogMapper aiGenerationLogMapper;
    @Mock
    private DatasetService datasetService;

    private AiSecretCipher cipher;
    private AiService aiService;

    @BeforeEach
    void setUp() {
        cipher = new AiSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        aiService = new AiService(aiConfigMapper, aiPromptTemplateMapper, aiGenerationLogMapper,
                datasetService, new ObjectMapper(), cipher, new AiPiiMasker(),
                mock(AiGovernanceService.class));
    }

    @Test
    void saveConfig_storesCipherOnly_noPlaintext() {
        AiConfigDTO dto = new AiConfigDTO();
        dto.setName("测试供应商");
        dto.setApiUrl("https://api.example.com/v1/chat/completions");
        dto.setApiKey(RAW_KEY);
        dto.setModelName("test-model");
        dto.setIsDefault(0);

        aiService.saveConfig(dto);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).insert(captor.capture());
        AiConfig saved = captor.getValue();

        assertNull(saved.getApiKey(), "实体中不得保留明文 API Key");
        assertNotNull(saved.getApiKeyCipher());
        assertFalse(saved.getApiKeyCipher().contains(RAW_KEY));
        assertEquals("sk-****0000", saved.getApiKeyMask());
        assertEquals(RAW_KEY, cipher.decrypt(saved.getApiKeyCipher()));
    }

    @Test
    void updateWithoutApiKey_keepsExistingCipher() {
        AiConfigDTO dto = new AiConfigDTO();
        dto.setId(1L);
        dto.setName("已有供应商");
        dto.setApiUrl("https://api.example.com/v1/chat/completions");
        dto.setApiKey("   ");
        dto.setModelName("test-model");
        dto.setIsDefault(0);

        aiService.saveConfig(dto);

        ArgumentCaptor<AiConfig> captor = ArgumentCaptor.forClass(AiConfig.class);
        verify(aiConfigMapper).updateById(captor.capture());
        AiConfig updated = captor.getValue();

        assertNull(updated.getApiKeyCipher(), "留空表示沿用原密钥，不应覆盖密文");
        assertNull(updated.getApiKey());
    }

    @Test
    void createWithoutApiKey_isRejected() {
        AiConfigDTO dto = new AiConfigDTO();
        dto.setName("无密钥");
        dto.setApiUrl("https://api.example.com/v1/chat/completions");
        dto.setModelName("test-model");

        var e = assertThrows(com.dataclean.exception.BusinessException.class,
                () -> aiService.saveConfig(dto));
        assertEquals(400, e.getCode());
        verify(aiConfigMapper, never()).insert(any(AiConfig.class));
    }

    @Test
    void listConfigs_returnsNoPlaintextAndOnlyMask() {
        AiConfig legacy = new AiConfig();
        legacy.setId(1L);
        legacy.setName("旧配置");
        legacy.setApiUrl("https://api.example.com");
        legacy.setApiKey(RAW_KEY);
        legacy.setApiKeyMask("sk-****3456");
        legacy.setModelName("m");
        when(aiConfigMapper.selectList(any())).thenReturn(List.of(legacy));

        List<AiConfigVO> result = aiService.listConfigs();

        assertEquals(1, result.size());
        assertTrue(result.get(0).getKeyConfigured());
        assertEquals("sk-****3456", result.get(0).getApiKeyMask());
        assertFalse(result.get(0).toString().contains(RAW_KEY));
    }

    @Test
    void entitySerialization_neverExposesApiKey() throws Exception {
        AiConfig config = new AiConfig();
        config.setId(1L);
        config.setApiKey(RAW_KEY);
        config.setApiKeyCipher("v1:abcdef");

        String json = new ObjectMapper().writeValueAsString(config);

        assertFalse(json.contains(RAW_KEY));
        assertFalse(json.contains("apiKey\""));
        assertFalse(json.contains("apiKeyCipher"));
    }
}
