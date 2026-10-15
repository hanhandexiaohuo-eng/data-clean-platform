package com.dataclean.config;

import com.dataclean.entity.User;
import com.dataclean.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化：创建默认管理员账户
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        User existing = userMapper.selectByUsername("admin");
        if (existing == null) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNickname("管理员");
            admin.setStatus(1);
            userMapper.insert(admin);
            log.info("默认管理员账户已创建: admin / admin123");
        } else {
            log.info("管理员账户已存在，跳过初始化");
        }
    }
}
