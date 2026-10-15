package com.dataclean.controller;

import com.dataclean.dto.LoginDTO;
import com.dataclean.dto.LoginVO;
import com.dataclean.dto.RegisterDTO;
import com.dataclean.dto.Result;
import com.dataclean.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto) {
        log.info("用户登录: {}", dto.getUsername());
        return Result.success("登录成功", userService.login(dto));
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<LoginVO> register(@RequestBody RegisterDTO dto) {
        log.info("用户注册: {}", dto.getUsername());
        return Result.success("注册成功", userService.register(dto));
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> getUserInfo() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = (Long) auth.getPrincipal();
        return Result.success(userService.getUserInfo(userId));
    }

    /**
     * 检查用户名是否可用
     */
    @GetMapping("/check-username")
    public Result<Boolean> checkUsername(@RequestParam String username) {
        // 简单的用户名格式检查
        boolean valid = username != null && username.length() >= 3 && username.matches("^[a-zA-Z0-9_]+$");
        return Result.success(valid);
    }
}
