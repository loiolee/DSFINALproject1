package com.example.surveysystem.utils;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** 生成 URL 友好的短 ID；数据库唯一索引是最终的并发冲突保障。 */
@Component
public class IdGenerator {
    private static final char[] ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public String shortId() {
        char[] value = new char[12];
        for (int i = 0; i < value.length; i++) {
            value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(value);
    }
}
