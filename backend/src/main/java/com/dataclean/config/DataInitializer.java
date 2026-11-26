package com.dataclean.config;

import com.dataclean.entity.User;
import com.dataclean.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化：可选创建初始管理员。
 *
 * <p>不再内置任何默认密码。仅当 ADMIN_USERNAME 与 ADMIN_PASSWORD 均已配置，
 * 且该用户名不存在时才创建；否则跳过。日志不输出密码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username:}")
    private String adminUsername;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (adminUsername == null || adminUsername.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            log.info("未配置初始管理员（ADMIN_USERNAME / ADMIN_PASSWORD），跳过初始化");
            return;
        }
        if (adminPassword.length() < 12) {
            log.warn("初始管理员密码长度不足 12 位，已跳过创建，请使用更强的密码");
            return;
        }

        User existing = userMapper.selectByUsername(adminUsername.trim());
        if (existing != null) {
            log.info("管理员账户已存在，跳过初始化");
            return;
        }

        User admin = new User();
        admin.setUsername(adminUsername.trim());
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setNickname("管理员");
        admin.setStatus(1);
        userMapper.insert(admin);
        log.info("初始管理员账户已创建: {}", admin.getUsername());
    }
}
