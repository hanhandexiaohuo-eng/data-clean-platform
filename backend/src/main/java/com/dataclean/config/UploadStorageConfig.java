package com.dataclean.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 上传目录初始化。
 *
 * <p>安全约束：
 * <ul>
 *   <li>上传根目录与临时目录由应用自行创建，权限收紧为 0700（POSIX 环境）；</li>
 *   <li>该目录不注册静态资源映射，Nginx 也只服务前端产物，浏览器无法直接访问；</li>
 *   <li>临时目录只用于容器解析 multipart，按 TTL 定期清理。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class UploadStorageConfig {

    private final Path root;

    public UploadStorageConfig(@Value("${upload.path:./uploads}") String uploadPath) {
        this.root = Paths.get(uploadPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(tempDir());
        restrict(root);
        restrict(tempDir());
        log.info("上传目录已就绪（不可静态访问，临时目录受 TTL 清理）");
    }

    public Path root() {
        return root;
    }

    public Path tempDir() {
        return root.resolve("tmp");
    }

    private void restrict(Path dir) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(dir, perms);
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 或无 POSIX 权限支持的环境跳过，权限由部署侧控制
            log.debug("跳过目录权限收紧: {}", e.getClass().getSimpleName());
        }
    }
}
