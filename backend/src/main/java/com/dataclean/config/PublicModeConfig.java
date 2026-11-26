package com.dataclean.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 公开模式基础设施：启用配置绑定与定时任务（匿名会话清理）。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({PublicModeProperties.class, NetworkSecurityProperties.class,
        ScanProperties.class, MonitoringProperties.class, JobProperties.class})
public class PublicModeConfig {
}
