package com.dataclean.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小化健康检查。
 *
 * <p>仅返回服务存活与数据库就绪状态，用于容器健康检查或受控代理探测。
 * 不返回数据库地址、账号、版本、堆栈、配置项或任何业务数据。
 *
 * <p>约定：
 * <ul>
 *   <li>数据库可用：HTTP 200，{"status":"UP","checks":{"database":"UP"}}；</li>
 *   <li>数据库不可用：HTTP 503，{"status":"DOWN","checks":{"database":"DOWN"}}。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseUp = isDatabaseReady();
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("database", databaseUp ? "UP" : "DOWN");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", databaseUp ? "UP" : "DOWN");
        body.put("checks", checks);

        if (databaseUp) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean isDatabaseReady() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            // 健康检查不输出异常详情，避免连接串/内部地址进入日志
            return false;
        }
    }
}
