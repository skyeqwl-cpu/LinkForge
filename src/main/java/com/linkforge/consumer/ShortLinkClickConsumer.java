package com.linkforge.consumer;

import com.linkforge.entity.ShortLinkClickLog;
import com.linkforge.event.ShortLinkClickEvent;
import com.linkforge.mapper.ShortLinkClickLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 短链接点击事件消费者。
 *
 * 设计要点（面试可讲）：
 * 1) 异步解耦 + 削峰：302 跳转主流程只做 Redis 自增 + 发 MQ，明细落库由本消费者异步完成，
 *    不阻塞用户跳转，突发流量由 MQ 缓冲。
 * 2) 双保险：点击"总量"以 Redis 计数（高性能，定时任务回写 MySQL）为准；
 *    "明细"以 MQ + click_log 为准。两者职责分离、互为兜底。
 * 3) 幂等：RocketMQ 是"至少一次"投递，网络抖动可能重复。用 Redis SETNX(eventId) 做去重窗口，
 *    同一 eventId 在 24h 内只落库一次，保证明细不重复。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "short-link-click-topic",
        consumerGroup = "short-link-click-consumer-group"
)
public class ShortLinkClickConsumer implements RocketMQListener<ShortLinkClickEvent> {

    private static final String IDEMPOTENT_KEY_PREFIX = "click_log:idempotent:";
    private static final long IDEMPOTENT_TTL_HOURS = 24;

    @Autowired
    private ShortLinkClickLogMapper clickLogMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(ShortLinkClickEvent event) {
        if (event == null || event.getShortCode() == null || event.getEventId() == null) {
            log.warn("收到非法点击事件，已忽略: {}", event);
            return;
        }

        // 1. 幂等去重：同一 eventId 在去重窗口内只处理一次
        String idemKey = IDEMPOTENT_KEY_PREFIX + event.getEventId();
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(idemKey, "1", IDEMPOTENT_TTL_HOURS, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(first)) {
            log.info("重复点击事件，跳过去重 eventId={}, shortCode={}", event.getEventId(), event.getShortCode());
            return;
        }

        // 2. 真实落库点击明细
        try {
            ShortLinkClickLog logEntity = new ShortLinkClickLog();
            logEntity.setShortCode(event.getShortCode());
            logEntity.setIp(event.getIp());
            logEntity.setUa(event.getUa());
            logEntity.setEventId(event.getEventId());
            // createTime 由 MyBatis-Plus 全局填充（INSERT）兜底，数据库也有 DEFAULT
            clickLogMapper.insert(logEntity);
            log.info("点击事件落库成功 shortCode={}, eventId={}", event.getShortCode(), event.getEventId());
        } catch (Exception e) {
            // 落库失败：释放去重标记，允许后续重试（at-least-once 语义下安全重试）
            stringRedisTemplate.delete(idemKey);
            log.error("点击事件落库失败 shortCode={}, eventId={}", event.getShortCode(), event.getEventId(), e);
        }
    }
}
