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

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 72, message = "密码长度必须为 8-72 个字符")
    private String password;
}
