package com.dataclean.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 恶意文件扫描配置（前缀 {@code upload.scan}）。
 *
 * <p>默认 fail-closed：公开模式要求必须接入可运行的扫描服务，扫描失败/超时/不可用
 * 一律拒绝上传。
 */
@Data
@ConfigurationProperties(prefix = "upload.scan")
public class ScanProperties {

    /** 是否强制要求扫描（公开模式默认 true，缺失实现时拒绝启动） */
    private boolean required = true;

    /** 扫描服务地址（仅 http 实现使用），不得写入日志 */
    private String url = "";

    /** 单次扫描超时（毫秒） */
    private int timeoutMs = 5000;

    /** 扫描响应体最大字节数，防止异常响应拖垮内存 */
    private int maxResponseBytes = 65536;

    /** 调用扫描服务的访问令牌（可选，仅通过 Header 传递，不记录日志） */
    private String token = "";
}
