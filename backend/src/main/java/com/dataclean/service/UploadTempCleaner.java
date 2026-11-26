package com.dataclean.service;

import com.dataclean.config.UploadStorageConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

/**
 * 上传临时文件 TTL 清理。
 *
 * <p>只清理上传根目录下 tmp/ 中的过期临时文件，且只统计数量、不输出文件名；
 * 解析成功或失败都不会留下原始文件（原始文件名从不落盘）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadTempCleaner {

    private final UploadStorageConfig storageConfig;

    @Value("${upload.temp-ttl-minutes:120}")
    private long tempTtlMinutes;

    @Scheduled(fixedDelayString = "#{60000 * ${upload.temp-clean-interval-minutes:60}}", initialDelay = 120000)
    public void cleanExpired() {
        Path tmp = storageConfig.tempDir();
        if (!Files.isDirectory(tmp)) {
            return;
        }
        Instant deadline = Instant.now().minus(tempTtlMinutes, ChronoUnit.MINUTES);
        int removed = 0;
        try (Stream<Path> files = Files.list(tmp)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                try {
                    if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toInstant().isBefore(deadline)) {
                        Files.deleteIfExists(path);
                        removed++;
                    }
                } catch (IOException e) {
                    log.debug("删除过期临时文件失败: {}", e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            log.debug("遍历临时目录失败: {}", e.getClass().getSimpleName());
        }
        if (removed > 0) {
            log.info("清理上传临时文件: {} 个", removed);
        }
    }
}
