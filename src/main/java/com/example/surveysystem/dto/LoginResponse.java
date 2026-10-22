package com.example.surveysystem.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** 注册或登录成功后的响应；token 是客户端后续请求使用的 Bearer JWT。 */
@Data
@Builder
public class LoginResponse {
    private String userId;
    private String username;
    private String token;
    private Instant expiresAt;
}
