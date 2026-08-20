package com.linkforge.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<String, String> shortLinkLocalCache() {
        return Caffeine.newBuilder()
                .initialCapacity(100)                  // 初始容量
                .maximumSize(10_000)                   // 最多缓存 1 万条热门短链（防止爆内存）
                .expireAfterWrite(5, TimeUnit.MINUTES) // 写入 5 分钟后过期（保持与 Redis 一致性）
                .build();
    }
}