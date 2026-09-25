package com.linkforge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 短链接点击明细表（由 RocketMQ 消费者异步写入）。
 * 与 short_link.click_count（Redis 计数 + 定时回写的总量）互补：
 * 本表提供"谁、何时、用什么设备点的"明细，支撑 PV/UV、地域、设备分析。
 */
@Data
@TableName("short_link_click_log")
public class ShortLinkClickLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shortCode;   // 短码
    private String ip;          // 访问者 IP
    private String ua;          // User-Agent（设备/浏览器）
    private String eventId;     // 事件幂等键（去重）

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
