package com.example.surveysystem.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/** 使用 HMAC-SHA256 签发和验证紧凑 JWT（HS256），不在令牌中放置密码等敏感信息。 */
@Component
@RequiredArgsConstructor
public class JwtUtil {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;

    @Value("${app.security.token-secret}")
    private String secret;

    @Value("${app.security.token-ttl-seconds:86400}")
    private long ttlSeconds;

    public TokenPair issue(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("JWT subject 不能为空");
        }
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        try {
            String header = ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
            byte[] payloadBytes = objectMapper.writeValueAsBytes(Map.of(
                    "sub", userId,
                    "iat", now.getEpochSecond(),
                    "exp", expiresAt.getEpochSecond()
            ));
            String payload = ENCODER.encodeToString(payloadBytes);
            String unsigned = header + "." + payload;
            return new TokenPair(unsigned + "." + sign(unsigned), expiresAt);
        } catch (Exception ex) {
            throw new IllegalStateException("生成登录 JWT 失败", ex);
        }
    }

    /** 验证 JWT 格式、算法、签名及过期时间；无效令牌统一返回 Optional.empty()。 */
    public Optional<String> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return Optional.empty();
        }
        String unsigned = parts[0] + "." + parts[1];
        if (!constantTimeEquals(parts[2], sign(unsigned))) return Optional.empty();
        try {
            JsonNode header = objectMapper.readTree(DECODER.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText()) || !"JWT".equals(header.path("typ").asText())) {
                return Optional.empty();
            }
            JsonNode payload = objectMapper.readTree(DECODER.decode(parts[1]));
            String subject = payload.path("sub").asText("");
            JsonNode expiryNode = payload.get("exp");
            if (subject.isBlank() || expiryNode == null || !expiryNode.canConvertToLong()) return Optional.empty();
            long expiresAt = expiryNode.asLong();
            if (Instant.now().getEpochSecond() >= expiresAt) return Optional.empty();
            return Optional.of(subject);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法签署登录 JWT", ex);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public record TokenPair(String token, Instant expiresAt) {}
}
