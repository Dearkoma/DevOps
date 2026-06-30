package com.devops.platform.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次性登录令牌服务 —— 限制登录只能从前端发起。
 * 前端先调 GET /api/auth/login-token 拿 token，登录时携带，
 * 每个 token 一次性使用，2 分钟过期。
 */
@Service
public class LoginTokenService {

    private static final long TOKEN_TTL_MS = 2 * 60 * 1000;

    private final Map<String, Long> tokenStore = new ConcurrentHashMap<>();

    /** 生成一个一次性登录 token */
    public String generate() {
        String token = UUID.randomUUID().toString();
        tokenStore.put(token, System.currentTimeMillis());
        return token;
    }

    /**
     * 验证并消费 token —— 有效则删除并返回 true，无效/过期/不存在返回 false。
     */
    public boolean validateAndConsume(String token) {
        if (token == null) return false;
        Long created = tokenStore.remove(token);
        if (created == null) return false;
        return System.currentTimeMillis() - created <= TOKEN_TTL_MS;
    }

    /** 每 5 分钟清理过期 token */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void cleanExpired() {
        long now = System.currentTimeMillis();
        tokenStore.entrySet().removeIf(e -> now - e.getValue() > TOKEN_TTL_MS);
    }
}
