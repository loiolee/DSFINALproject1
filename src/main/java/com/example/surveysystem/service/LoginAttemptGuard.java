package com.example.surveysystem.service;

import com.example.surveysystem.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败次数限制：抵御针对单个账号的在线撞库/暴力破解。
 *
 * <p>实现为单实例内存滑动窗口。已知取舍：按用户名计数意味着攻击者可以恶意锁定某个账号；
 * 生产环境通常再叠加按来源 IP 限流，并使用 Redis 等共享存储以支持多实例部署。</p>
 */
@Component
public class LoginAttemptGuard {

    /** 窗口内允许的失败次数。 */
    private static final int MAX_FAILURES = 10;
    /** 滑动窗口长度。 */
    private static final Duration WINDOW = Duration.ofMinutes(5);
    /** 记录条目超过该数量时触发一次清理，避免长期运行内存膨胀。 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    /** 在尝试登录前调用；已超出限制则直接拒绝。 */
    public void assertAllowed(String username) {
        Deque<Instant> record = failures.get(key(username));
        if (record == null) {
            return;
        }
        synchronized (record) {
            prune(record);
            if (record.size() >= MAX_FAILURES) {
                throw BusinessException.tooManyRequests(
                        "登录失败次数过多，请 " + WINDOW.toMinutes() + " 分钟后再试");
            }
        }
    }

    public void recordFailure(String username) {
        cleanupIfNeeded();
        Deque<Instant> record = failures.computeIfAbsent(key(username), k -> new ArrayDeque<>());
        synchronized (record) {
            prune(record);
            record.addLast(Instant.now());
        }
    }

    public void recordSuccess(String username) {
        failures.remove(key(username));
    }

    private void prune(Deque<Instant> record) {
        Instant threshold = Instant.now().minus(WINDOW);
        while (!record.isEmpty() && record.peekFirst().isBefore(threshold)) {
            record.pollFirst();
        }
    }

    private void cleanupIfNeeded() {
        if (failures.size() < CLEANUP_THRESHOLD) {
            return;
        }
        failures.entrySet().removeIf(entry -> {
            Deque<Instant> record = entry.getValue();
            synchronized (record) {
                prune(record);
                return record.isEmpty();
            }
        });
    }

    /** 用户名大小写不敏感，避免用不同大小写绕过计数。 */
    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
