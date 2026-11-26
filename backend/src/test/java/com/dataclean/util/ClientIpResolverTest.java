package com.dataclean.util;

import com.dataclean.config.NetworkSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 可信客户端 IP 解析测试：伪造代理头必须被忽略。
 */
class ClientIpResolverTest {

    private ClientIpResolver resolver(String... cidrs) {
        NetworkSecurityProperties properties = new NetworkSecurityProperties();
        properties.setTrustedProxies(List.of(cidrs));
        return new ClientIpResolver(properties);
    }

    @Test
    void untrustedRemoteCannotSpoofProxyHeaders() {
        ClientIpResolver resolver = resolver("10.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Real-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "8.8.8.8");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void trustedProxyUsesRealIp() {
        ClientIpResolver resolver = resolver("127.0.0.1/32", "172.16.0.0/12");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.18.0.5");
        request.addHeader("X-Real-IP", "198.51.100.7");

        assertEquals("198.51.100.7", resolver.resolve(request));
    }

    @Test
    void trustedProxyIgnoresInvalidRealIpAndFallsBackToForwardedRightmostHop() {
        ClientIpResolver resolver = resolver("127.0.0.1/32");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", "not-an-ip; DROP TABLE");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.23");

        assertEquals("198.51.100.23", resolver.resolve(request));
    }

    @Test
    void invalidForwardedHeaderFallsBackToRemoteAddress() {
        ClientIpResolver resolver = resolver("127.0.0.1/32");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "evil, <script>");

        assertEquals("127.0.0.1", resolver.resolve(request));
    }

    @Test
    void ipv6LoopbackIsTrustedAndNormalized() {
        ClientIpResolver resolver = resolver("::1/128");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        request.addHeader("X-Real-IP", "2001:db8::1");

        assertEquals("2001:db8:0:0:0:0:0:1", resolver.resolve(request));
    }
}
