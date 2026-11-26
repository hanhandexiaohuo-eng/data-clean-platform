package com.dataclean.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 运行模式。
 *
 * <p>由 {@code APP_MODE} 决定，默认 {@code private}：
 * <ul>
 *   <li>{@code private}：保留登录页，可访问全部真实数据与管理能力；</li>
 *   <li>{@code public}：免登录匿名会话，只能访问演示数据与本会话临时数据。</li>
 * </ul>
 */
@Slf4j
@Component
public class AppMode {

    private final String mode;

    public AppMode(@Value("${app.mode:private}") String mode) {
        this.mode = mode == null ? "private" : mode.trim().toLowerCase();
        if (!"public".equals(this.mode) && !"private".equals(this.mode)) {
            throw new IllegalStateException("APP_MODE 只能是 public 或 private");
        }
    }

    public boolean isPublic() {
        return "public".equals(mode);
    }

    public boolean isPrivate() {
        return !isPublic();
    }

    public String value() {
        return mode;
    }
}
