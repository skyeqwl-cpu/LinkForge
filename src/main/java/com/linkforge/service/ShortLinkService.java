package com.linkforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.dto.ShortLinkCreateDTO;
import com.linkforge.entity.ShortLink;
import com.linkforge.exception.BizException;
import com.linkforge.mapper.ShortLinkMapper;
import com.linkforge.utils.Base62Utils;
import com.linkforge.vo.ShortLinkVO;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.concurrent.TimeUnit;

@Service
public class ShortLinkService {
    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final String SHORT_LINK_KEY_PREFIX = "short_link:";//短链接缓存前缀
    private static final String NULL_VALUE = "__NULL__";

    // 1. 生成短链接
    @Transactional
    public ShortLinkVO createShortLink(ShortLinkCreateDTO dto) {
        // 先插入一条只有 originUrl 的数据，获取自增 ID
        ShortLink shortLink = new ShortLink();
        shortLink.setOriginUrl(dto.getOriginUrl());
        shortLinkMapper.insert(shortLink);

        // 根据自增 ID 转成 Base62 短码
        String shortCode = Base62Utils.encode(shortLink.getId());

        // 更新 short_code 回数据库
        shortLink.setShortCode(shortCode);
        shortLinkMapper.updateById(shortLink);

        // 生成时顺便预热写入 Redis 缓存（缓存 7 天）
        String redisKey = SHORT_LINK_KEY_PREFIX + shortCode;//短链接缓存键
        // 缓存 7 天, 值为 originUrl
        stringRedisTemplate.opsForValue().set(redisKey, dto.getOriginUrl(), 7, TimeUnit.DAYS);

        //return shortCode;
        String domain = "http://localhost:8081/";
        return ShortLinkVO.builder()
                .shortCode(shortCode)
                .shortUrl(domain + shortCode)
                .originUrl(dto.getOriginUrl())
                .build();
    }

    // 2. 根据短码获取长网址
    public String getOriginUrl(String shortCode) {

        // 1. Redis Key
        String redisKey = SHORT_LINK_KEY_PREFIX + shortCode;

        // 2. 查询 Redis
        String originUrl = stringRedisTemplate
                .opsForValue()
                .get(redisKey);

        // 3. Redis 命中
        if (originUrl != null) {

            // 命中空值缓存
            if (NULL_VALUE.equals(originUrl)) {
                System.out.println("====== Redis 命中：空值 ======");
                System.out.println("shortCode: " + shortCode);

                throw new BizException(404, "短链接不存在或已失效");
            }

            // 正常命中
            System.out.println("====== Redis 命中 ======");
            System.out.println("shortCode: " + shortCode);
            System.out.println("originUrl: " + originUrl);

            return originUrl;
        }

        // 4. Redis 未命中
        System.out.println("====== Redis 未命中 ======");
        System.out.println("shortCode: " + shortCode);

        // 5. 获取分布式锁
        String lockKey = "lock:short_link:" + shortCode;
        RLock lock = redissonClient.getLock(lockKey);

        try {

            // 6. 尝试获取锁
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            System.out.println("====== 尝试获取分布式锁 ======");
            System.out.println("shortCode: " + shortCode);
            System.out.println("线程: " + Thread.currentThread().getName());

            if (!locked) {
                System.out.println("====== 获取分布式锁失败 ======");
                System.out.println("shortCode: " + shortCode);

                throw new BizException(429, "系统繁忙，请稍后重试");
            }

            System.out.println("====== 成功获取分布式锁 ======");
            System.out.println("shortCode: " + shortCode);
            System.out.println("线程: " + Thread.currentThread().getName());

            // 7. Double Check
            originUrl = stringRedisTemplate
                    .opsForValue()
                    .get(redisKey);

            if (originUrl != null) {

                System.out.println("====== Double Check：Redis 已有数据 ======");
                System.out.println("shortCode: " + shortCode);

                if (NULL_VALUE.equals(originUrl)) {
                    throw new BizException(404, "短链接不存在或已失效");
                }

                System.out.println("originUrl: " + originUrl);

                return originUrl;
            }

            // 8. Redis 依然没有 → 查询 MySQL
            System.out.println("====== Redis 未命中，查询 MySQL ======");
            System.out.println("shortCode: " + shortCode);

            LambdaQueryWrapper<ShortLink> wrapper =
                    new LambdaQueryWrapper<>();

            wrapper.eq(ShortLink::getShortCode, shortCode);

            ShortLink shortLink =
                    shortLinkMapper.selectOne(wrapper);

            // 9. MySQL 不存在
            if (shortLink == null) {

                System.out.println("====== MySQL 不存在，写入 Redis 空值 ======");
                System.out.println("shortCode: " + shortCode);

                // 防止缓存穿透
                stringRedisTemplate.opsForValue()
                        .set(
                                redisKey,
                                NULL_VALUE,
                                5,
                                TimeUnit.MINUTES
                        );

                throw new BizException(
                        404,
                        "短链接不存在或已失效"
                );
            }

            // 10. MySQL 查询成功
            originUrl = shortLink.getOriginUrl();

            System.out.println("====== MySQL 查询成功，写入 Redis ======");
            System.out.println("shortCode: " + shortCode);
            System.out.println("originUrl: " + originUrl);

            stringRedisTemplate.opsForValue()
                    .set(
                            redisKey,
                            originUrl,
                            7,
                            TimeUnit.DAYS
                    );

            return originUrl;

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new BizException(
                    500,
                    "获取短链接失败"
            );

        } finally {

            // 11. 释放分布式锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

   /* @PostConstruct
    public void test() {
        System.out.println(redissonClient);
    }*/

}
