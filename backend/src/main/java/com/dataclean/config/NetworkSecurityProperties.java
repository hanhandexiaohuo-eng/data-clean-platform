package com.dataclean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网络边界配置（前缀 {@code app.network}）。
 *
 * <p>只有来自 {@link #trustedProxies} 中 CIDR 的直连请求，才会采信
 * {@code X-Real-IP}/{@code X-Forwarded-For}；其余请求一律使用 TCP 连接地址，
 * 防止客户端伪造代理头绕过 IP 摘要与限流。
 */
@Data
@ConfigurationProperties(prefix = "app.network")
public class NetworkSecurityProperties {

    /** 受信反向代理 CIDR 列表（逗号分隔）；默认仅本机回环 */
    private List<String> trustedProxies = new ArrayList<>(List.of("127.0.0.1/32", "::1/128"));
}
