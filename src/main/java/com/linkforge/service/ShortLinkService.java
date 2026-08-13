package com.linkforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkforge.common.PageResult;
import com.linkforge.dto.ShortLinkCreateDTO;
import com.linkforge.entity.ShortLink;
import com.linkforge.exception.BizException;
import com.linkforge.mapper.ShortLinkMapper;
import com.linkforge.utils.Base62Utils;
import com.linkforge.vo.ShortLinkDetailVO;
import com.linkforge.vo.ShortLinkManageVO;
import com.linkforge.vo.ShortLinkStatsVO;
import com.linkforge.vo.ShortLinkVO;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    // 访问统计
    private static final String CLICK_COUNT_KEY_PREFIX = "short_link:click:";
    private static final String LAST_ACCESS_KEY_PREFIX = "short_link:last_access:";

    // 阶段 12 缓存雪崩防护：基础 TTL + 随机抖动，避免大量 key 同时过期压垮 MySQL
    private static final long CACHE_BASE_TTL_DAYS = 7;
    private static final long CACHE_TTL_JITTER_MINUTES = 10;

    // ==================== 阶段 10：缓存一致性（Cache Aside Pattern） ====================
    // 10.1 创建：MySQL 成功 → 再写 Redis
    //   顺序：先落库（insert + 回写 shortCode），再预热缓存。
    //   遵循「Cache Aside 写路径」：数据库是唯一真相源，缓存只是派生数据。
    //   注：当前 Redis 写入仍在 @Transactional 内，Redis 故障会回滚 DB（见 deleteShortLink 注释里的优化点）。
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

        // 生成时顺便预热写入 Redis 缓存（基础 7 天 + 随机抖动，防雪崩）
        String redisKey = SHORT_LINK_KEY_PREFIX + shortCode;//短链接缓存键
        stringRedisTemplate.opsForValue().set(redisKey, dto.getOriginUrl(), cacheTtlSeconds(), TimeUnit.SECONDS);

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

            // ==============================
            // Redis 命中
            // ==============================
            System.out.println("====== Redis 命中 ======");
            System.out.println("shortCode: " + shortCode);
            System.out.println("originUrl: " + originUrl);

            // 记录访问
            recordAccess(shortCode);

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

                // 记录访问
                recordAccess(shortCode);

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

            // 写入短链接缓存（基础 7 天 + 随机抖动，防雪崩）
            stringRedisTemplate.opsForValue()
                    .set(
                            redisKey,
                            originUrl,
                            cacheTtlSeconds(),
                            TimeUnit.SECONDS
                    );

            // 记录访问
            recordAccess(shortCode);

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

    /**
     * 记录短链接访问
     */
    public void recordAccess(String shortCode) {

        // 1. 访问次数 +1
        String clickCountKey = CLICK_COUNT_KEY_PREFIX + shortCode;

        Long count = stringRedisTemplate
                .opsForValue()
                .increment(clickCountKey);

        // 2. 记录最后访问时间
        String lastAccessKey = LAST_ACCESS_KEY_PREFIX + shortCode;

        stringRedisTemplate.opsForValue().set(
                lastAccessKey,
                String.valueOf(System.currentTimeMillis())
        );

        System.out.println("====== 记录访问 ======");
        System.out.println("shortCode: " + shortCode);
        System.out.println("clickCount: " + count);
    }
    /**
     * 将 Redis 点击次数同步到 MySQL
     */
    public void syncClickCountToMySQL(String shortCode) {

        String clickKey = CLICK_COUNT_KEY_PREFIX + shortCode;

        // 1. 获取 Redis 点击次数
        String clickValue = stringRedisTemplate
                .opsForValue()
                .get(clickKey);

        if (clickValue == null) {
            System.out.println("====== Redis 没有点击数据 ======");
            System.out.println("shortCode: " + shortCode);
            return;
        }

        long clickCount = Long.parseLong(clickValue);

        System.out.println("====== Redis 点击次数 ======");
        System.out.println("shortCode: " + shortCode);
        System.out.println("clickCount: " + clickCount);

        // 2. 查询 MySQL
        LambdaQueryWrapper<ShortLink> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(ShortLink::getShortCode, shortCode);

        ShortLink shortLink =
                shortLinkMapper.selectOne(wrapper);

        if (shortLink == null) {
            System.out.println("====== MySQL 短链接不存在 ======");
            System.out.println("shortCode: " + shortCode);
            return;
        }

        // 3. 更新 MySQL 点击次数
        shortLink.setClickCount(clickCount);

        shortLinkMapper.updateById(shortLink);

        System.out.println("====== MySQL 点击次数同步成功 ======");
        System.out.println("shortCode: " + shortCode);
        System.out.println("clickCount: " + clickCount);
    }

   /* @PostConstruct
    public void test() {
        System.out.println(redissonClient);
    }*/
    /**
     * 查询短链接访问统计
     */
    public ShortLinkStatsVO getStats(String shortCode) {

        // 1. 查询 MySQL 中的短链接
        LambdaQueryWrapper<ShortLink> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(ShortLink::getShortCode, shortCode);

        ShortLink shortLink =
                shortLinkMapper.selectOne(wrapper);

        // 2. 短链接不存在
        if (shortLink == null) {
            throw new BizException(
                    404,
                    "短链接不存在或已失效"
            );
        }

        // 3. 查询 Redis 点击次数
        String clickKey =
                CLICK_COUNT_KEY_PREFIX + shortCode;

        String clickValue =
                stringRedisTemplate
                        .opsForValue()
                        .get(clickKey);

        Long clickCount;

        if (clickValue == null) {
            // Redis 没有统计数据时，使用 MySQL 数据
            clickCount = shortLink.getClickCount();

            if (clickCount == null) {
                clickCount = 0L;
            }
        } else {
            clickCount = Long.parseLong(clickValue);
        }

        // 4. 查询 Redis 最后访问时间
        String lastAccessKey =
                LAST_ACCESS_KEY_PREFIX + shortCode;

        String lastAccessValue =
                stringRedisTemplate
                        .opsForValue()
                        .get(lastAccessKey);

        String lastAccessTime = null;

        if (lastAccessValue != null) {

            long timestamp =
                    Long.parseLong(lastAccessValue);

            lastAccessTime =
                    new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss"
                    ).format(
                            new java.util.Date(timestamp)
                    );
        }

        // 5. 返回统计信息
        return ShortLinkStatsVO.builder()
                .shortCode(shortCode)
                .originUrl(shortLink.getOriginUrl())
                .clickCount(clickCount)
                .lastAccessTime(lastAccessTime)
                .build();
    }
    /**
     * 查询短链接详细统计信息
     */
    public ShortLinkDetailVO getShortLinkDetail(String shortCode) {

        // 1. 查询 MySQL
        LambdaQueryWrapper<ShortLink> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(ShortLink::getShortCode, shortCode);

        ShortLink shortLink =
                shortLinkMapper.selectOne(wrapper);

        // 2. 短链接不存在
        if (shortLink == null) {
            throw new BizException(
                    404,
                    "短链接不存在或已失效"
            );
        }

        // 3. 获取 Redis 点击次数
        String clickKey =
                CLICK_COUNT_KEY_PREFIX + shortCode;

        String clickValue =
                stringRedisTemplate
                        .opsForValue()
                        .get(clickKey);

        Long clickCount;

        if (clickValue == null) {
            // Redis 没有统计数据，就使用 MySQL 数据
            clickCount = shortLink.getClickCount();

            if (clickCount == null) {
                clickCount = 0L;
            }

        } else {
            clickCount = Long.parseLong(clickValue);
        }

        // 4. 获取最后访问时间
        String lastAccessKey =
                LAST_ACCESS_KEY_PREFIX + shortCode;

        String lastAccessValue =
                stringRedisTemplate
                        .opsForValue()
                        .get(lastAccessKey);

        String lastAccessTime = null;

        if (lastAccessValue != null) {

            long timestamp =
                    Long.parseLong(lastAccessValue);

            lastAccessTime =
                    java.time.Instant
                            .ofEpochMilli(timestamp)
                            .atZone(
                                    java.time.ZoneId.systemDefault()
                            )
                            .toLocalDateTime()
                            .toString();
        }

        // 5. 组装 VO
        return ShortLinkDetailVO.builder()
                .shortCode(shortCode)
                .originUrl(shortLink.getOriginUrl())
                .createTime(shortLink.getCreateTime())
                .clickCount(clickCount)
                .lastAccessTime(lastAccessTime)
                .build();
    }

    // ==================== 阶段 8：短链接管理接口 ====================

    private static final DateTimeFormatter MANAGE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 8.1 查询单个短链接
     */
    public ShortLinkManageVO getShortLinkByCode(String shortCode) {
        ShortLink shortLink = shortLinkMapper.selectOne(
                new LambdaQueryWrapper<ShortLink>()
                        .eq(ShortLink::getShortCode, shortCode)
        );
        if (shortLink == null) {
            throw new BizException(404, "短链接不存在或已失效");
        }
        // 点击数优先取 Redis 实时值，否则回退 MySQL 持久化值
        Long clickCount = resolveClickCount(shortCode, shortLink.getClickCount());
        return toManageVO(shortLink, clickCount);
    }

    /**
     * 8.2 查询短链接列表（不分页，全部返回，按创建时间倒序）
     */
    public List<ShortLinkManageVO> listShortLinks() {
        List<ShortLink> list = shortLinkMapper.selectList(
                new LambdaQueryWrapper<ShortLink>()
                        .orderByDesc(ShortLink::getCreateTime)
        );
        return list.stream()
                .map(e -> toManageVO(e, e.getClickCount()))
                .collect(Collectors.toList());
    }

    /**
     * 8.3 分页查询短链接
     * 使用 MyBatis-Plus 的 Page<ShortLink>
     */
    public PageResult<ShortLinkManageVO> listShortLinksByPage(long page, long size) {
        Page<ShortLink> pageReq = new Page<>(page, size);
        Page<ShortLink> pageResult = shortLinkMapper.selectPage(
                pageReq,
                new LambdaQueryWrapper<ShortLink>()
                        .orderByDesc(ShortLink::getCreateTime)
        );

        List<ShortLinkManageVO> records = pageResult.getRecords().stream()
                .map(e -> toManageVO(e, e.getClickCount()))
                .collect(Collectors.toList());

        PageResult<ShortLinkManageVO> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(pageResult.getTotal());
        result.setPage(page);
        result.setSize(size);
        return result;
    }

    /**
     * 阶段 9：删除 / 失效短链接（阶段 10 的 10.2 缓存一致性规则）
     * 10.2 删除：MySQL 删除 → 再删 Redis
     *   第一版做「物理清理」：MySQL 记录 + 所有相关 Redis 键一起删，
     *   避免「MySQL 删了但 Redis 还在、用户仍能访问」的脏数据问题。
     *   删除后下一次访问会：Redis 未命中 → MySQL 未命中 → 写入 NULL_VALUE → 返回 404，
     *   因此链接被正确失效。
     *
     * 阶段 10 待落地规则（10.3 修改，当前无 update 接口）：
     *   未来增加修改接口时，务必遵循「MySQL 更新 → 删 Redis（而非更新缓存）→ 下次访问重载」。
     *   原因：并发下「先更新 DB 再更新缓存」可能留下旧缓存值；「更新 DB 后删缓存」
     *   更稳妥——下次读未命中会自动从 DB 重新 load，天然一致。
     *
     * 优化点（一致性 ↔ 可用性）：本方法 Redis 删除在 @Transactional 内。
     *   若 Redis 故障抛异常，会连累 MySQL 删除回滚。理想做法：先提交 DB 事务，
     *   再删缓存；缓存删除失败仅记录告警/补偿，不回滚 DB。
     */
    @Transactional
    public void deleteShortLink(String shortCode) {
        // 1. 查询是否存在
        ShortLink shortLink = shortLinkMapper.selectOne(
                new LambdaQueryWrapper<ShortLink>()
                        .eq(ShortLink::getShortCode, shortCode)
        );
        if (shortLink == null) {
            throw new BizException(404, "短链接不存在或已失效");
        }

        // 2. 删除 MySQL 记录
        shortLinkMapper.deleteById(shortLink.getId());

        // 3. 删除 Redis 相关键
        //    short_link:2 / short_link:click:2 / short_link:last_access:2
        stringRedisTemplate.delete(SHORT_LINK_KEY_PREFIX + shortCode);
        stringRedisTemplate.delete(CLICK_COUNT_KEY_PREFIX + shortCode);
        stringRedisTemplate.delete(LAST_ACCESS_KEY_PREFIX + shortCode);
    }

    /**
     * 解析点击数：优先 Redis 实时计数，缺失时回退 MySQL 持久化值
     */
    private Long resolveClickCount(String shortCode, Long fallback) {
        String clickValue = stringRedisTemplate
                .opsForValue()
                .get(CLICK_COUNT_KEY_PREFIX + shortCode);
        if (clickValue != null) {
            return Long.parseLong(clickValue);
        }
        return fallback == null ? 0L : fallback;
    }

    /**
     * 实体 → 管理视图对象（createTime 序列化为字符串）
     */
    private ShortLinkManageVO toManageVO(ShortLink e, Long clickCount) {
        String createTime = e.getCreateTime() == null ? null
                : e.getCreateTime().format(MANAGE_TIME_FORMATTER);
        return ShortLinkManageVO.builder()
                .shortCode(e.getShortCode())
                .originUrl(e.getOriginUrl())
                .createTime(createTime)
                .clickCount(clickCount)
                .build();
    }

    /**
     * 阶段 12：缓存雪崩防护
     * 基础 TTL（7 天）+ 随机抖动（0~10 分钟），将热点 key 的过期时间打散，
     * 避免「同一时刻大量 key 同时失效 → 瞬间大量回源 MySQL」的雪崩。
     */
    private long cacheTtlSeconds() {
        long jitterSeconds = ThreadLocalRandom.current()
                .nextLong(0, CACHE_TTL_JITTER_MINUTES + 1) * 60;
        return TimeUnit.DAYS.toSeconds(CACHE_BASE_TTL_DAYS) + jitterSeconds;
    }

}
