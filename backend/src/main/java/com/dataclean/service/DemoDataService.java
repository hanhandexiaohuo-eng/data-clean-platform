package com.dataclean.service;

import com.dataclean.config.AppMode;
import com.dataclean.config.PublicModeProperties;
import com.dataclean.entity.Dataset;
import com.dataclean.util.FileParserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * 公开模式演示数据初始化。
 *
 * <p>演示数据为人工构造的样例 CSV，不含任何真实业务数据或个人信息；
 * 仅 public + {@code app.public.demo-data-enabled=true} 时创建，且可重复执行（存在则跳过）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataService implements ApplicationRunner {

    private static final String DEMO_NAME = "官方演示数据 - 销售样例";
    private static final String DEMO_DESC = "人工构造的公开演示数据，仅用于体验导入、清洗与可视化功能；不含真实业务信息。";

    private final AppMode appMode;
    private final PublicModeProperties properties;
    private final DatasetService datasetService;

    @Override
    public void run(ApplicationArguments args) {
        if (!appMode.isPublic() || !properties.isDemoDataEnabled()) {
            return;
        }
        try (Reader reader = new InputStreamReader(
                new ClassPathResource("demo/demo_sales.csv").getInputStream(), StandardCharsets.UTF_8)) {
            FileParserUtil.ParseResult parsed = FileParserUtil.parseCsv(reader, true);
            Dataset dataset = datasetService.createDemoDataset(DEMO_NAME, DEMO_DESC,
                    parsed.headers(), parsed.data());
            log.info("演示数据集就绪: id={}, rows={}", dataset.getId(), parsed.rowCount());
        } catch (Exception e) {
            // 演示数据失败不应阻塞应用启动
            log.warn("演示数据初始化失败: {}", e.getClass().getSimpleName());
        }
    }
}
