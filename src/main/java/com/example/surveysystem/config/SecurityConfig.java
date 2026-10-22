package com.example.surveysystem.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * 只提供 BCrypt 编码器，不引入 Spring Security 的过滤器链（认证由 JWT 拦截器负责）。
 * 同时在启动时检查密钥配置，避免带着仓库里的演示密钥对外提供服务。
 */
@Slf4j
@Configuration
public class SecurityConfig {

    /** 仓库中 application.yml 的默认演示密钥，出现在运行环境里说明没有替换。 */
    private static final List<String> DEMO_SECRETS = List.of(
            "local-dev-token-secret-change-before-deployment-2026",
            "local-dev-hmac-secret-change-before-deployment-2026");
    /** 密钥最短长度建议值。 */
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${app.security.token-secret}")
    private String tokenSecret;

    @Value("${app.security.hmac-secret}")
    private String hmacSecret;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @PostConstruct
    void checkSecrets() {
        check("app.security.token-secret", tokenSecret);
        check("app.security.hmac-secret", hmacSecret);
    }

    private void check(String name, String value) {
        if (value == null || value.isBlank()) {
            // 密钥为空会让签名失效，属于配置错误，直接拒绝启动而不是静默降级。
            throw new IllegalStateException(name + " 未配置，拒绝启动");
        }
        if (DEMO_SECRETS.contains(value)) {
            log.warn("{} 仍在使用 application.yml 中的演示密钥，任何人都可据此伪造令牌；"
                    + "对外提供服务前请通过环境变量替换", name);
        } else if (value.length() < MIN_SECRET_LENGTH) {
            log.warn("{} 长度不足 {} 位，签名强度偏低，建议使用更长的随机串", name, MIN_SECRET_LENGTH);
        }
    }
}
