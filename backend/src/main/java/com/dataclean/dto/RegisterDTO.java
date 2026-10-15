package com.dataclean.dto;

import lombok.Data;

/**
 * 注册请求 DTO
 */
@Data
public class RegisterDTO {
    private String username;
    private String password;
    private String nickname;
}
