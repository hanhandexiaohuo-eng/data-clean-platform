package com.dataclean.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * 跨域配置。
 *
 * <p>生产环境由 Nginx 提供同源反向代理，正常不需要跨域。仅当确实存在
 * 独立域名前端时，才通过 CORS_ALLOWED_ORIGINS 显式列出来源（逗号分隔）；
 * 未配置时不放行任何跨域来源，且不再使用「任意来源 + 携带凭证」的组合。
 *
 * <p>该 Bean 由 Spring Security 的 CORS 支持自动加载，预检请求在认证前处理。
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(false);

        if (StringUtils.hasText(allowedOrigins)) {
            Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(origin -> !origin.isEmpty() && !"*".equals(origin))
                    .forEach(config::addAllowedOrigin);
        }

        config.addAllowedHeader("Authorization");
        config.addAllowedHeader("Content-Type");
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("OPTIONS");
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
