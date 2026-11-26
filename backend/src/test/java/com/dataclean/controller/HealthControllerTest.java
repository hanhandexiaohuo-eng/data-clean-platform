package com.dataclean.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康检查接口测试：只暴露最小状态，失败时不泄露连接信息。
 */
class HealthControllerTest {

    @Test
    void returnsUpWhenDatabaseIsReady() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = new HealthController(dataSource).health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals(Set.of("status", "checks"), response.getBody().keySet());
        assertEquals("UP", checks(response).get("database"));
    }

    @Test
    void returnsDownWhenConnectionInvalid() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = new HealthController(dataSource).health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", checks(response).get("database"));
    }

    @Test
    void returnsDownWithoutLeakingConnectionDetails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(
                new SQLException("connect failed: internal-db-host, credential=secret-value"));

        ResponseEntity<Map<String, Object>> response = new HealthController(dataSource).health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        String body = response.getBody().toString();
        assertEquals("DOWN", response.getBody().get("status"));
        assertFalse(body.contains("internal-db-host"));
        assertFalse(body.contains("secret-value"));
        assertFalse(body.contains("credential"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checks(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("checks");
    }
}
