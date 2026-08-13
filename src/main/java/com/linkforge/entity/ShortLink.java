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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private Long clickCount;
}