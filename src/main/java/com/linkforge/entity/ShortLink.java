package com.linkforge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("short_link")
public class ShortLink {

    @TableId(type = IdType.AUTO) // 依赖数据库自增主键
    private Long id;

    private String shortCode;

    private String originUrl;

    private LocalDateTime createTime;
}