package com.linkforge.config;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BloomFilterConfig {

    @Bean //@Bean的作用是将方法返回的对象注册为 Spring 容器中的 Bean
    public RBloomFilter<String> shortLinkBloomFilter(RedissonClient redissonClient) {
        //RBloomFilter是Redisson提供的布隆过滤器实现类，用于快速判断元素是否存在
        //RedissonClient 是 Redis 客户端，用于与 Redis 服务器交互的 API

        // 创建布隆过滤器
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("short_link_bloom_filter");
        // 初始化布隆过滤器：预估容量 100 万，误判率 1% (0.01)
        bloomFilter.tryInit(1000000L, 0.01);
        return bloomFilter;
    }
}
