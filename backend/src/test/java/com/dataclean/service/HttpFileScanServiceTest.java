package com.dataclean.service;

import com.dataclean.config.ScanProperties;
import com.dataclean.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 扫描客户端测试：通过本机真实 HTTP 服务验证通过/检出/超时/不可用/异常响应，
 * 并确认错误信息不泄露扫描地址与样本内容。
 */
class HttpFileScanServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private HttpFileScanService service(String url, int timeoutMs, int maxBytes) {
        ScanProperties properties = new ScanProperties();
        properties.setUrl(url);
        properties.setTimeoutMs(timeoutMs);
        properties.setToken("test-token");
        properties.setMaxResponseBytes(maxBytes);
        return new HttpFileScanService(properties, new ObjectMapper());
    }

    private String startServer(int status, String body, long delayMs, AtomicReference<String> tokenRef) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scan", exchange -> {
            try {
                tokenRef.set(exchange.getRequestHeaders().getFirst("X-Scan-Token"));
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/scan";
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void cleanFilePassesAndTokenIsSent() throws IOException {
        AtomicReference<String> token = new AtomicReference<>();
        String url = startServer(200, "{\"clean\":true}", 0, token);

        assertDoesNotThrow(() -> service(url, 2000, 65536).scan(file("data.csv", "a,b\n1,2"), "data.csv", "csv"));
        assertEquals("test-token", token.get());
    }

    @Test
    void maliciousFileIsRejectedWithoutLeakingDetails() throws IOException {
        String url = startServer(200, "{\"clean\":false,\"reason\":\"EICAR-TEST\"}", 0, new AtomicReference<>());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service(url, 2000, 65536).scan(file("evil.csv", "EICAR"), "evil.csv", "csv"));

        assertEquals(400, e.getCode());
        assertFalse(e.getMessage().contains("EICAR"));
        assertFalse(e.getMessage().contains("127.0.0.1"));
        assertFalse(e.getMessage().contains(url));
    }

    @Test
    void timeoutIsRejected() throws IOException {
        String url = startServer(200, "{\"clean\":true}", 1500, new AtomicReference<>());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service(url, 300, 65536).scan(file("slow.csv", "a,b"), "slow.csv", "csv"));

        assertEquals(503, e.getCode());
        assertFalse(e.getMessage().contains("127.0.0.1"));
    }

    @Test
    void unavailableServiceIsRejected() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        BusinessException e = assertThrows(BusinessException.class,
                () -> service("http://127.0.0.1:" + port + "/scan", 1000, 65536)
                        .scan(file("data.csv", "a,b"), "data.csv", "csv"));

        assertEquals(503, e.getCode());
    }

    @Test
    void serverErrorIsRejected() throws IOException {
        String url = startServer(500, "internal scanner path /secret", 0, new AtomicReference<>());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service(url, 1000, 65536).scan(file("data.csv", "a,b"), "data.csv", "csv"));

        assertEquals(503, e.getCode());
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void ambiguousResponseIsRejected() throws IOException {
        String url = startServer(200, "{\"result\":\"unknown\"}", 0, new AtomicReference<>());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service(url, 1000, 65536).scan(file("data.csv", "a,b"), "data.csv", "csv"));

        assertEquals(503, e.getCode());
    }

    @Test
    void oversizedResponseIsRejected() throws IOException {
        String url = startServer(200, "{\"clean\":true,\"padding\":\"" + "x".repeat(4096) + "\"}", 0, new AtomicReference<>());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service(url, 2000, 128).scan(file("data.csv", "a,b"), "data.csv", "csv"));

        assertEquals(503, e.getCode());
    }

    @Test
    void missingUrlIsRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service("", 1000, 65536).scan(file("data.csv", "a,b"), "data.csv", "csv"));

        assertEquals(503, e.getCode());
        assertTrue(e.getMessage().contains("暂不可用"));
    }
}
