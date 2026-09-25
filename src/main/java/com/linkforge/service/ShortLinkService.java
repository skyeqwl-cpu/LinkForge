package com.linkforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.linkforge.common.LoginUserContext;
import com.linkforge.common.PageResult;
import com.linkforge.dto.ShortLinkCreateDTO;
import com.linkforge.config.JwtUtil;
import com.linkforge.entity.ShortLink;
import com.linkforge.event.ShortLinkClickEvent;
import com.linkforge.exception.BizException;
import com.linkforge.mapper.ShortLinkMapper;
import com.linkforge.utils.Base62Utils;
import com.linkforge.vo.ShortLinkDetailVO;
import com.linkforge.vo.ShortLinkManageVO;
import com.linkforge.vo.ShortLinkStatsVO;
import com.linkforge.vo.ShortLinkVO;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class ShortLinkService {
    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RBloomFilter<String> shortLinkBloomFilter;

    @Autowired
    private Cache<String, String> shortLinkLocalCache;

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 管理员用户 ID 白名单（来自配置 linkforge.admin-user-ids，逗号分隔）。
     * 名单内的用户拥有「最大权限」：访问任何短链（含「仅创建者」可见的私有链）均放行，
     * 但仍需携带有效登录 token（先证明自己是管理员，再享特权）。
     * 默认空字符串 → 无管理员，保持原有访问控制行为不变。
     */
    @Value("${linkforge.admin-user-ids:}")
    private String adminUserIdsRaw;
    private Set<Long> adminUserIds = Set.of();

    private static final String SHORT_LINK_KEY_PREFIX = "short_link:";//短链接缓存前缀

    // 访问限制元数据缓存：short_link:meta:{code} = "{accessType}:{ownerId}"，与短链缓存同生命周期
    private static final String META_KEY_PREFIX = "short_link:meta:";
    private static final int PUBLIC_CODE = 0;   // 无限制（任何人可访问）
    private static final int PRIVATE_CODE = 1;  // 仅创建者可访问
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:"; // 与 JwtInterceptor 黑名单前缀一致
    private static final String NULL_VALUE = "__NULL__";

    // 访问统计
    private static final String CLICK_COUNT_KEY_PREFIX = "short_link:click:";
    private static final String LAST_ACCESS_KEY_PREFIX = "short_link:last_access:";

    // 阶段 12 缓存雪崩防护：基础 TTL + 随机抖动，避免大量 key 同时过期压垮 MySQL
    private static final long CACHE_BASE_TTL_DAYS = 7;
    private static final long CACHE_TTL_JITTER_MINUTES = 10;

    // 阶段 13：短链接生命周期
    private static final int DEFAULT_VALID_DAYS = 7;            // 默认有效期（天）
    private static final String EXPIRE_KEY_PREFIX = "short_link:expire:"; // 过期时间戳缓存键

    // 短链接域名（用于拼装 shortUrl，统一管理避免硬编码散落）
    private static final String SHORT_LINK_DOMAIN = "http://localhost:8081/";

    private static final Logger log = LoggerFactory.getLogger(ShortLinkService.class);

    // ==================== 阶段 10：缓存一致性（Cache Aside Pattern） ====================
    // 10.1 创建：MySQL 成功 → 再写 Redis
    //   顺序：先落库（insert + 回写 shortCode），再预热缓存。
    //   遵循「Cache Aside 写路径」：数据库是唯一真相源，缓存只是派生数据。
    //   注：当前 Redis 写入仍在 @Transactional 内，Redis 故障会回滚 DB（见 deleteShortLink 注释里的优化点）。
    @Transactional
    public ShortLinkVO createShortLink(ShortLinkCreateDTO dto) {
        // URL 安全校验：仅允许 http/https 协议、且 host 非空。
        // @URL 注解已做格式校验，这里再用 URI 做深度防御，
        // 拦截 javascript:、data:、file: 等危险协议，防止 XSS / 钓鱼短链。
        validateUrl(dto.getOriginUrl());

        // 先插入一条只有 originUrl 的数据，获取自增 ID
        ShortLink shortLink = new ShortLink();
        shortLink.setOriginUrl(dto.getOriginUrl());
        // 多用户：归属当前登录用户（JWT 拦截器已写入上下文）
        shortLink.setUserId(LoginUserContext.getUserId());

        // 访问限制类型：null → 无限制(PUBLIC_CODE=0)，持久化到 access_type 列
        int accessType = dto.getAccessType() != null ? dto.getAccessType() : PUBLIC_CODE;
        shortLink.setAccessType(accessType);

        // 6.4 计算过期时间：默认 7 天，用户可传 validDays 覆盖
        int validDays = dto.getValidDays() != null ? dto.getValidDays() : DEFAULT_VALID_DAYS;
        LocalDateTime expireTime = LocalDateTime.now().plusDays(validDays);
        shortLink.setExpireTime(expireTime);

        shortLinkMapper.insert(shortLink);

        // 根据自增 ID 转成 Base62 短码
        String shortCode = Base62Utils.encode(shortLink.getId());

        // 更新 short_code 回数据库
        shortLink.setShortCode(shortCode);
        shortLinkMapper.updateById(shortLink);

        // 新增：把生成的 shortCode 写入布隆过滤器
        shortLinkBloomFilter.add(shortCode);

        // 6.5 预热写入 Redis 缓存：TTL = 距过期剩余秒数 + 随机抖动（防雪崩）
        String redisKey = SHORT_LINK_KEY_PREFIX + shortCode;//短链接缓存键
        long ttl = cacheTtlSeconds(expireTime);
        stringRedisTemplate.opsForValue().set(redisKey, dto.getOriginUrl(), ttl, TimeUnit.SECONDS);

        // 6.6 业务层二次过期校验用的过期时间戳：与短链缓存同 TTL 自动淘汰
        stringRedisTemplate.opsForValue().set(
                EXPIRE_KEY_PREFIX + shortCode,
                String.valueOf(toEpochSeconds(expireTime)),
                ttl,
                TimeUnit.SECONDS
        );

        // 6.7 写入访问限制元数据缓存（与短链缓存同 TTL）：让 resolveMeta 直接命中，
        // 避免首访回源 MySQL；同时确保「仅创建者」权限在创建后即可生效（否则 access_type 恒为 0）。
        stringRedisTemplate.opsForValue().set(
                META_KEY_PREFIX + shortCode,
                AccessMeta.of(accessType, LoginUserContext.getUserId()).toText(),
                ttl,
                TimeUnit.SECONDS
        );

        //return shortCode;
        String domain = SHORT_LINK_DOMAIN;
        return ShortLinkVO.builder()
                .shortCode(shortCode)
                .shortUrl(domain + shortCode)
                .originUrl(dto.getOriginUrl())
                .expireTime(expireTime.format(MANAGE_TIME_FORMATTER))
                .build();
    }

    /**
     * URL 安全校验（深度防御）。
     * DTO 上的 @URL 注解已做格式校验，这里再用 java.net.URI 做二次校验：
     * 1. 仅允许 http / https 协议，拦截 javascript:、data:、file:、vbscript: 等危险协议；
     * 2. host 必须非空，拦截形如 "http://" 的残缺 URL。
     * 目的：防止攻击者构造危险协议短链，用户点击后触发 XSS / 钓鱼。
     */
    private void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "URL 格式不正确");
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BizException(400, "仅支持 http / https 协议的 URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BizException(400, "URL 缺少有效的域名");
        }
    }

    // 2. 根据短码获取长网址
    public String getOriginUrl(String shortCode) {

        // 【第一道防线】：布隆过滤器拦截
        if (!shortLinkBloomFilter.contains(shortCode)) {
            throw new BizException(404, "短链接不存在或已失效");
        }

        // 【防线】：查 L1 Caffeine 本地缓存（0 网络 I/O，极速返回）
        String originUrl = shortLinkLocalCache.getIfPresent(shortCode);
        if (originUrl != null) {
            if (NULL_VALUE.equals(originUrl)) {
                throw new BizException(404, "短链接不存在或已失效");
            }
            recordAccess(shortCode); // 异步/原子记录访问
            return originUrl;
        }

        // 1. Redis Key
        String redisKey = SHORT_LINK_KEY_PREFIX + shortCode;

        // 2. 查询 Redis
         originUrl = stringRedisTemplate
                .opsForValue()
                .get(redisKey);

        // 3. Redis 命中
        if (originUrl != null) {

            // 命中空值缓存
            if (NULL_VALUE.equals(originUrl)) {

                throw new BizException(404, "短链接不存在或已失效");
            }

            // 6.6 业务层二次过期校验：即使 Redis Key 仍存活，也可能因 TTL 抖动略超期
            if (isExpiredAndClean(shortCode, redisKey)) {

                throw new BizException(404, "短链接已过期失效");
            }

            // ====================================================
            // 【关键修复】：Redis 命中后，同步回写到 L1 Caffeine 本地缓存
            // ====================================================
            shortLinkLocalCache.put(shortCode, originUrl);

            // 记录访问
            recordAccess(shortCode);

            return originUrl;
        }

        // 4. Redis 未命中

        // 5. 获取分布式锁
        String lockKey = "lock:short_link:" + shortCode;
        RLock lock = redissonClient.getLock(lockKey);

        try {

            // 6. 尝试获取锁
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);

            if (!locked) {

                throw new BizException(429, "系统繁忙，请稍后重试");
            }


            // 7. Double Check
            originUrl = stringRedisTemplate
                    .opsForValue()
                    .get(redisKey);

            if (originUrl != null) {


                if (NULL_VALUE.equals(originUrl)) {
                    throw new BizException(404, "短链接不存在或已失效");
                }

                // 6.6 业务层二次过期校验
                if (isExpiredAndClean(shortCode, redisKey)) {
                    throw new BizException(404, "短链接已过期失效");
                }


                // 记录访问
                recordAccess(shortCode);

                return originUrl;
            }

            // 8. Redis 依然没有 → 查询 MySQL

            LambdaQueryWrapper<ShortLink> wrapper =
                    new LambdaQueryWrapper<>();

            wrapper.eq(ShortLink::getShortCode, shortCode);

            ShortLink shortLink =
                    shortLinkMapper.selectOne(wrapper);

            // 9. MySQL 不存在
            if (shortLink == null) {


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

            // 6.6 过期校验：数据库里已过期 → 视为失效，写短 TTL 空值避免反复打 DB
            if (shortLink.getExpireTime() != null
                    && toEpochSeconds(shortLink.getExpireTime()) <= Instant.now().getEpochSecond()) {

                stringRedisTemplate.opsForValue()
                        .set(redisKey, NULL_VALUE, 5, TimeUnit.MINUTES);

                throw new BizException(404, "短链接已过期失效");
            }

            // 10. MySQL 查询成功
            originUrl = shortLink.getOriginUrl();


            // 写入短链缓存（TTL = 距过期剩余秒数 + 随机抖动，防雪崩）
            long ttl = cacheTtlSeconds(shortLink.getExpireTime());
            stringRedisTemplate.opsForValue()
                    .set(
                            redisKey,
                            originUrl,
                            ttl,
                            TimeUnit.SECONDS
                    );

            // 写入过期时间戳（与短链缓存同 TTL），供业务层二次过期校验
            if (shortLink.getExpireTime() != null) {
                stringRedisTemplate.opsForValue().set(
                        EXPIRE_KEY_PREFIX + shortCode,
                        String.valueOf(toEpochSeconds(shortLink.getExpireTime())),
                        ttl,
                        TimeUnit.SECONDS
                );
            }

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
     * 记录短链接访问：
     * 1) Redis 原子自增计数（总量，定时任务批量回写 MySQL，最终一致）
     * 2) 异步投递点击事件到 RocketMQ（明细，由消费者落库 click_log，幂等去重）
     * 两者解耦：计数走 Redis 保性能，明细走 MQ 保可靠，互为双保险。
     */
    public void recordAccess(String shortCode) {

        // 1. 访问次数 +1（Redis 是计数真相源）
        String clickCountKey = CLICK_COUNT_KEY_PREFIX + shortCode;
        Long count = stringRedisTemplate.opsForValue().increment(clickCountKey);

        // 2. 记录最后访问时间
        String lastAccessKey = LAST_ACCESS_KEY_PREFIX + shortCode;
        stringRedisTemplate.opsForValue().set(
                lastAccessKey,
                String.valueOf(System.currentTimeMillis())
        );

        // 3. 采集访问上下文（IP / UA），用于点击明细
        String ip = "unknown";
        String ua = "unknown";
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                ip = getClientIp(request);
                String header = request.getHeader("User-Agent");
                if (header != null && !header.isEmpty()) {
                    ua = header;
                }
            }
        } catch (Exception ignored) {
            // 非 Web 上下文（如单测）忽略
        }

        // 4. 异步投递到 RocketMQ（不阻塞 302 跳转主流程）
        String eventId = shortCode + ":" + System.nanoTime() + ":" + ThreadLocalRandom.current().nextInt(100000);
        ShortLinkClickEvent event = new ShortLinkClickEvent(shortCode, System.currentTimeMillis(), ip, ua, eventId);
        try {
            rocketMQTemplate.asyncSend("short-link-click-topic", event, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.debug("MQ 点击事件发送成功: {}", shortCode);
                }

                @Override
                public void onException(Throwable e) {
                    log.warn("MQ 点击事件发送失败(不影响跳转): {}", e.getMessage());
                }
            });
        } catch (Exception e) {
            // MQ 不可用（如 broker 未启动）时静默降级，跳转流程不受影响
            log.warn("MQ 生产者不可用，点击事件未投递: {}", e.getMessage());
        }
    }

    /**
     * 解析客户端真实 IP（兼容反向代理 X-Forwarded-For）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else {
            ip = ip.split(",")[0].trim();
        }
        return ip;
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
            return;
        }

        long clickCount = Long.parseLong(clickValue);


        // 2. 查询 MySQL
        LambdaQueryWrapper<ShortLink> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(ShortLink::getShortCode, shortCode);

        ShortLink shortLink =
                shortLinkMapper.selectOne(wrapper);

        if (shortLink == null) {
            return;
        }

        // 3. 更新 MySQL 点击次数
        shortLink.setClickCount(clickCount);

        shortLinkMapper.updateById(shortLink);

    }

   /* @PostConstruct
    public void test() {
    }*/
    /**
     * 查询短链接访问统计
     */
    public ShortLinkStatsVO getStats(String shortCode) {

        // 1. 查询 MySQL 中的短链接（且必须属于当前用户）
        LambdaQueryWrapper<ShortLink> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(ShortLink::getShortCode, shortCode)
               .eq(ShortLink::getUserId, LoginUserContext.getUserId());

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

        // 1. 查询 MySQL（且必须属于当前用户）
        LambdaQueryWrapper<ShortLink> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(ShortLink::getShortCode, shortCode)
               .eq(ShortLink::getUserId, LoginUserContext.getUserId());

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
                .expireTime(shortLink.getExpireTime())
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
                        .eq(ShortLink::getUserId, LoginUserContext.getUserId())
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
                        .eq(ShortLink::getUserId, LoginUserContext.getUserId())
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
                        .eq(ShortLink::getUserId, LoginUserContext.getUserId())
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
        // 1. 查询是否存在（且必须属于当前用户，避免删到别人的短链）
        ShortLink shortLink = shortLinkMapper.selectOne(
                new LambdaQueryWrapper<ShortLink>()
                        .eq(ShortLink::getShortCode, shortCode)
                        .eq(ShortLink::getUserId, LoginUserContext.getUserId())
        );
        if (shortLink == null) {
            throw new BizException(404, "短链接不存在或已失效");
        }

        // 2. 删除 MySQL 记录
        shortLinkMapper.deleteById(shortLink.getId());

        // 清理 L1 本地缓存
        shortLinkLocalCache.invalidate(shortCode);

        // 3. 删除 Redis 相关键
        //    short_link:2 / short_link:click:2 / short_link:last_access:2 / short_link:expire:2
        stringRedisTemplate.delete(SHORT_LINK_KEY_PREFIX + shortCode);
        stringRedisTemplate.delete(META_KEY_PREFIX + shortCode);
        stringRedisTemplate.delete(CLICK_COUNT_KEY_PREFIX + shortCode);
        stringRedisTemplate.delete(LAST_ACCESS_KEY_PREFIX + shortCode);
        stringRedisTemplate.delete(EXPIRE_KEY_PREFIX + shortCode);
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
        String expireTime = e.getExpireTime() == null ? null
                : e.getExpireTime().format(MANAGE_TIME_FORMATTER);
        return ShortLinkManageVO.builder()
                .shortCode(e.getShortCode())
                .shortUrl(SHORT_LINK_DOMAIN + e.getShortCode())
                .originUrl(e.getOriginUrl())
                .createTime(createTime)
                .expireTime(expireTime)
                .clickCount(clickCount)
                .build();
    }

    private long cacheTtlSeconds(LocalDateTime expireTime) {
        long jitterSeconds = ThreadLocalRandom.current()
                .nextLong(0, CACHE_TTL_JITTER_MINUTES + 1) * 60;
        if (expireTime == null) {
            // 无过期时间（长期有效）→ 回落到基础 7 天 TTL
            return TimeUnit.DAYS.toSeconds(CACHE_BASE_TTL_DAYS) + jitterSeconds;
        }
        // 距过期剩余秒数（至少 60 秒，避免过期时间已过时 TTL 为负）
        long remaining = java.time.Duration.between(LocalDateTime.now(), expireTime).getSeconds();
        return Math.max(remaining, 60) + jitterSeconds;
    }

    /**
     * 阶段 13：LocalDateTime → 绝对秒级时间戳（按系统默认时区）
     * 用于把 expire_time 与「当前秒」做比较，判断短链是否过期。
     */
    private long toEpochSeconds(LocalDateTime time) {
        return time.atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * 6.6 业务层二次过期校验（旁路缓存一致性）。
     * 读取 Redis 中缓存的过期时间戳（short_link:expire:{code}）：
     * - 若该短链已过期，清理短链缓存与过期时间戳，返回 true（调用方按失效处理）；
     * - 若无过期信息（历史缓存），返回 false，信任 Redis TTL。
     * 这样即便 Redis Key 因 TTL 抖动仍存活，也不会返回已过期链接。
     */
    private boolean isExpiredAndClean(String shortCode, String redisKey) {
        String expireVal = stringRedisTemplate
                .opsForValue()
                .get(EXPIRE_KEY_PREFIX + shortCode);
        if (expireVal == null) {
            return false;
        }
        long expireEpoch = Long.parseLong(expireVal);
        long nowEpoch = Instant.now().getEpochSecond();
        if (expireEpoch > 0 && expireEpoch <= nowEpoch) {
            stringRedisTemplate.delete(redisKey);
            stringRedisTemplate.delete(EXPIRE_KEY_PREFIX + shortCode);
            return true;
        }
        return false;
    }

    /**
     * 管理员白名单初始化（配置 linkforge.admin-user-ids，逗号分隔的用户 ID）。
     * 名单内的用户访问受限短链（仅创建者可见）时绕过 owner 校验 → 最大权限。
     * 仍要求携带有效 token，避免未登录即获得特权。
     */
    @PostConstruct
    public void initAdminUserIds() {
        this.adminUserIds = Arrays.stream(adminUserIdsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toSet());
    }

    private boolean isAdmin(Long userId) {
        return userId != null && adminUserIds.contains(userId);
    }

    // ==================== 访问限制（公开 / 仅创建者） ====================
    /**
     * 带访问控制的跳转解析。
     * - 无限制(PUBLIC)：任何人可访问（保留原行为）；
     * - 仅创建者(PRIVATE)：必须登录且为 owner，否则 401/403；
     * 通过后再走原有布隆/缓存/过期解析并记录访问。
     */
    public RedirectResult resolveRedirect(String shortCode, String authHeader) {
        AccessMeta meta = resolveMeta(shortCode);

        if (meta != null && meta.accessType == PRIVATE_CODE) {
            Long visitorId = resolveUserIdFromHeader(authHeader);
            if (visitorId == null) {
                return RedirectResult.unauthorized();
            }
            // 管理员白名单：拥有最大权限，可访问任何短链（含仅创建者可见的），无需是 owner
            if (!isAdmin(visitorId) && (meta.ownerId == null || !visitorId.equals(meta.ownerId))) {
                return RedirectResult.forbidden();
            }
        }

        try {
            String originUrl = getOriginUrl(shortCode);
            return RedirectResult.ok(originUrl);
        } catch (BizException e) {
            if (e.getCode() != null && e.getCode() == 404) {
                return RedirectResult.notFound();
            }
            throw e; // 429 / 500 透传给全局异常处理器
        }
    }

    /**
     * 解析短链访问元数据（访问类型 + 创建者 ID）。
     * 优先读 Redis 元数据缓存；缺失则回源 MySQL 并回填（与短链缓存同 TTL）。
     * 返回 null 表示短链不存在（DB 也无）。
     */
    private AccessMeta resolveMeta(String shortCode) {
        String metaKey = META_KEY_PREFIX + shortCode;
        String metaVal = stringRedisTemplate.opsForValue().get(metaKey);
        if (NULL_VALUE.equals(metaVal)) {
            return null; // 已知不存在
        }
        if (metaVal != null) {
            return AccessMeta.parse(metaVal);
        }
        ShortLink sl = shortLinkMapper.selectOne(
                new LambdaQueryWrapper<ShortLink>().eq(ShortLink::getShortCode, shortCode));
        if (sl == null) {
            stringRedisTemplate.opsForValue().set(metaKey, NULL_VALUE, 5, TimeUnit.MINUTES);
            return null;
        }
        Integer at = sl.getAccessType() != null ? sl.getAccessType() : PUBLIC_CODE;
        long ttl = cacheTtlSeconds(sl.getExpireTime());
        stringRedisTemplate.opsForValue()
                .set(metaKey, AccessMeta.of(at, sl.getUserId()).toText(), ttl, TimeUnit.SECONDS);
        return AccessMeta.of(at, sl.getUserId());
    }

    /**
     * 从 Authorization 头解析当前登录用户 ID，逻辑与 JwtInterceptor 保持一致（含登出黑名单）。
     * 未登录 / token 失效 / 已登出 → 返回 null（匿名，无法访问受限短链）。
     */
    private Long resolveUserIdFromHeader(String authHeader) {
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7).trim();
        try {
            Long userId = jwtUtil.parseUserId(token);
            String jti = jwtUtil.getJti(token);
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(BLACKLIST_PREFIX + jti))) {
                return null;
            }
            return userId;
        } catch (Exception e) {
            return null;
        }
    }

    /** 访问元数据：访问类型 + 创建者 ID（缓存文本格式 "{accessType}:{ownerId}"） */
    private static class AccessMeta {
        final int accessType;
        final Long ownerId;

        AccessMeta(int accessType, Long ownerId) {
            this.accessType = accessType;
            this.ownerId = ownerId;
        }

        static AccessMeta of(Integer accessType, Long ownerId) {
            return new AccessMeta(accessType == null ? PUBLIC_CODE : accessType, ownerId);
        }

        static AccessMeta parse(String text) {
            int idx = text.indexOf(':');
            int at = Integer.parseInt(text.substring(0, idx));
            String ownerStr = text.substring(idx + 1);
            Long ownerId = "null".equals(ownerStr) ? null : Long.parseLong(ownerStr);
            return new AccessMeta(at, ownerId);
        }

        String toText() {
            return accessType + ":" + (ownerId == null ? "null" : ownerId);
        }
    }

}
