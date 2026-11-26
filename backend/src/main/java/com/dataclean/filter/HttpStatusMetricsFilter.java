package com.dataclean.filter;

import com.dataclean.metrics.RuntimeMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 状态码聚合指标：只按 429 / 4xx / 5xx 分桶计数，
 * 不记录 URL、查询串、来源地址或响应内容。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class HttpStatusMetricsFilter extends OncePerRequestFilter {

    private final RuntimeMetrics metrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            metrics.httpStatus(response.getStatus());
        }
    }
}
