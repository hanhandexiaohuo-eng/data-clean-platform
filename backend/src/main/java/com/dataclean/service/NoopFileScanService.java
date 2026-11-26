package com.dataclean.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 未接入外部扫描服务时的默认实现：不做判定，只记录一次上线前运维提示。
 *
 * <p>即使没有扫描服务，上传内容也不会落盘、不会被执行，也不会被浏览器直接下载：
 * 只解析进动态表，原始文件名仅作为元信息展示。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "upload.scan-provider", havingValue = "none", matchIfMissing = true)
public class NoopFileScanService implements FileScanService {

    @Override
    public void scan(MultipartFile file, String safeFileName, String fileType) {
        log.debug("未配置恶意文件扫描服务，跳过扫描: type={}", fileType);
    }
}
