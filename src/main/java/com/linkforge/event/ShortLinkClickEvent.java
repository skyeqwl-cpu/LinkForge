package com.linkforge.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 短链接点击事件。
 * 由 ShortLinkService.recordAccess 在 302 跳转时投递到 RocketMQ，
 * 由 ShortLinkClickConsumer 消费并落库 click_log（明细）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ShortLinkClickEvent {
    private String shortCode;
    private Long timestamp;
    private String ip;        // 访问者真实 IP（兼容 X-Forwarded-For）
    private String ua;        // User-Agent
    private String eventId;   // 幂等去重键：同一事件在去重窗口内只落库一次
}
