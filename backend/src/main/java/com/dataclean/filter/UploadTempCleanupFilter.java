package com.dataclean.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 上传临时文件即时清理。
 *
 * <p>Servlet 容器只在对象回收时才删除 multipart 临时文件，解析失败或进程长期存活时
 * 文件会滞留。这里在请求结束后立即删除本次请求产生的所有 Part 临时文件，
 * 作为 TTL 定时清理之外的第一道兜底；仅记录数量，不记录文件名。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class UploadTempCleanupFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            String contentType = request.getContentType();
            if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
                int deleted = 0;
                try {
                    for (Part part : request.getParts()) {
                        try {
                            part.delete();
                            deleted++;
                        } catch (Exception ignored) {
                            // 单个 Part 删除失败不影响响应
                        }
                    }
                } catch (Exception ignored) {
                    // 解析失败或已释放时忽略
                }
                if (deleted > 0) {
                    log.debug("已清理上传临时文件 {} 个", deleted);
                }
            }
        }
    }
}
