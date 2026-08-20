package com.linkforge.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("short_link")
public class ShortLink {

    @TableId(type = IdType.AUTO) // 依赖数据库自增主键
    private Long id;

    private String shortCode;

    private String originUrl;

    // 多用户平台：所属用户 id（NULL 仅兼容迁移前的历史数据，新数据必定有值）
    @TableField("user_id")
    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private Long clickCount;

    // 阶段 13：短链接生命周期，过期时间（NULL 表示长期有效）
    @TableField("expire_time")
    private LocalDateTime expireTime;
}