package com.example.surveysystem.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** 使用服务端密钥计算 HMAC-SHA256；不要把 HMAC 密钥放进前端或提交到公开仓库。 */
@Component
public class HashUtils {
    @Value("${app.security.hmac-secret}")
    private String secret;

    public String respondentHash(String surveyId, String userId) {
        return hmacSha256(surveyId + ":" + userId);
    }

    private String hmacSha256(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("当前环境不支持 HMAC-SHA256", ex);
        }
    }
}
