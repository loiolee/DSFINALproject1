package com.example.surveysystem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 40, message = "用户名长度必须为 3-40 个字符")
    @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "用户名仅允许字母、数字、下划线、点和连字符")
    private String username;

    /**
     * 注册密码：8–16 位。
     *
     * <p>上限只作用于注册，不作用于登录：第一阶段允许 8–72 位，已有账号里可能存在超过 16 位的密码，
     * 若在 {@link LoginRequest} 上同样加上限，这些账号会直接无法登录。登录侧只校验非空。</p>
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 16, message = "密码长度必须为 8-16 个字符")
    private String password;
}
