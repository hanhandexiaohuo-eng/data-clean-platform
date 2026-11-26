package com.dataclean.util;

import com.dataclean.config.NetworkSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 可信客户端 IP 解析。
 *
 * <p>安全规则：
 * <ul>
 *   <li>直连地址不在受信代理 CIDR 内时，忽略一切代理头，直接使用 TCP 来源地址；</li>
 *   <li>直连地址受信时，仅采信 Nginx 覆盖写入的 {@code X-Real-IP}（单个合法 IP）；</li>
 *   <li>{@code X-Forwarded-For} 只作为兜底，并取最右侧一跳（最近代理看到的地址），
 *       避免客户端在最左侧塞入伪造地址；</li>
 *   <li>任何非法值直接回退到 TCP 来源地址，不做 DNS 解析。</li>
 * </ul>
 */
@Slf4j
@Component
public class ClientIpResolver {

    private static final Pattern IPV4 =
            Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    private final List<Cidr> trusted;

    public ClientIpResolver(NetworkSecurityProperties properties) {
        List<Cidr> parsed = new ArrayList<>();
        if (properties != null && properties.getTrustedProxies() != null) {
            for (String raw : properties.getTrustedProxies()) {
                if (raw == null) {
                    continue;
                }
                for (String part : raw.split(",")) {
                    String value = part.trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    Cidr cidr = Cidr.parse(value);
                    if (cidr == null) {
                        log.warn("忽略非法的受信代理配置（应为 CIDR 或 IP）");
                    } else {
                        parsed.add(cidr);
                    }
                }
            }
        }
        this.trusted = List.copyOf(parsed);
    }

    /** 解析可信客户端 IP；永不返回代理头中的非法值。 */
    public String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (remote == null) {
            return "0.0.0.0";
        }
        if (!isTrusted(remote)) {
            return remote;
        }
        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = normalize(hops[i]);
                if (hop != null) {
                    return hop;
                }
            }
        }
        return remote;
    }

    public boolean isTrusted(String ip) {
        byte[] bytes = toBytes(ip);
        if (bytes == null) {
            return false;
        }
        for (Cidr cidr : trusted) {
            if (cidr.matches(bytes)) {
                return true;
            }
        }
        return false;
    }

    /** 规范化并校验字面量 IP；非法或空返回 null。 */
    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        // 允许 [IPv6]:port 形式，去掉方括号与端口
        if (candidate.startsWith("[")) {
            int end = candidate.indexOf(']');
            if (end <= 0) {
                return null;
            }
            candidate = candidate.substring(1, end);
        } else if (IPV4.matcher(candidate).matches()) {
            return candidate;
        }
        // 只接受字面量，不做 DNS；非法字符直接拒绝
        if (!isLiteralCandidate(candidate)) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            return address.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLiteralCandidate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = Character.isDigit(c) || c == '.' || c == ':'
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '%';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static byte[] toBytes(String ip) {
        String normalized = normalize(ip);
        if (normalized == null) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized).getAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /** 极简 CIDR 匹配：仅比较前缀字节，不依赖第三方库。 */
    private record Cidr(byte[] network, int prefix) {

        static Cidr parse(String value) {
            String ipPart = value;
            int prefix = -1;
            int slash = value.indexOf('/');
            if (slash >= 0) {
                ipPart = value.substring(0, slash);
                try {
                    prefix = Integer.parseInt(value.substring(slash + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            byte[] bytes = toBytes(ipPart);
            if (bytes == null) {
                return null;
            }
            int max = bytes.length * 8;
            if (prefix < 0) {
                prefix = max;
            }
            if (prefix < 0 || prefix > max) {
                return null;
            }
            return new Cidr(bytes, prefix);
        }

        boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
