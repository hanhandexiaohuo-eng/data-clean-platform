package com.dataclean.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 恶意文件扫描预留接口。
 *
 * <p>公开模式上传前必须先经过本接口；接入 ClamAV、云存储内容审核或网关病毒扫描时，
 * 提供实现并设置 {@code upload.scan-provider} 即可替换默认的空实现。
 * 扫描失败必须抛出 {@link com.dataclean.exception.BusinessException}，拒绝入库。
 */
public interface FileScanService {

    /**
     * @param file         上传文件（只读取流，不保存原始文件）
     * @param safeFileName 已净化的展示文件名（不参与路径拼接）
     * @param fileType     识别后的文件类型：csv / xlsx / xls
     */
    void scan(MultipartFile file, String safeFileName, String fileType);
}
