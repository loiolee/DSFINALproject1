package com.example.surveysystem.service;

import com.example.surveysystem.domain.User;
import com.example.surveysystem.dto.LoginRequest;
import com.example.surveysystem.dto.LoginResponse;
import com.example.surveysystem.dto.RegisterRequest;
import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.repository.UserRepository;
import com.example.surveysystem.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptGuard loginAttemptGuard;

    /**
     * 用于「用户不存在」分支的占位哈希：无论账号是否存在都执行一次 BCrypt 比对，
     * 使两条分支的耗时接近，避免通过响应时间枚举出哪些用户名已注册。
     */
    private volatile String dummyHash;

    public LoginResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw BusinessException.conflict("用户名已存在");
        }
        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .createdAt(Instant.now())
                .build();
        try {
            user = userRepository.save(user);
        } catch (DuplicateKeyException ex) {
            // existsByUsername 与 save 之间仍可能发生并发注册，因此必须以唯一索引兜底。
            throw BusinessException.conflict("用户名已存在");
        }
        return loginResult(user);
    }

    public LoginResponse login(LoginRequest request) {
        loginAttemptGuard.assertAllowed(request.getUsername());
        User user = userRepository.findByUsername(request.getUsername()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.getPassword(), dummyHash());
            loginAttemptGuard.recordFailure(request.getUsername());
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptGuard.recordFailure(request.getUsername());
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        loginAttemptGuard.recordSuccess(request.getUsername());
        return loginResult(user);
    }

    /** 惰性生成一次占位哈希，供「用户不存在」分支做等代价比对。 */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            synchronized (this) {
                if (dummyHash == null) {
                    dummyHash = passwordEncoder.encode("not-a-real-password-" + UUID.randomUUID());
                }
                hash = dummyHash;
            }
        }
        return hash;
    }

    private LoginResponse loginResult(User user) {
        JwtUtil.TokenPair tokenPair = jwtUtil.issue(user.getId());
        return LoginResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .token(tokenPair.token())
                .expiresAt(tokenPair.expiresAt())
                .build();
    }
}
