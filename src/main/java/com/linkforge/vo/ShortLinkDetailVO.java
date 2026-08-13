package com.linkforge.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShortLinkDetailVO {

    /**
     * 短码
     */
    private String shortCode;

    /**
     * 原始长链接
     */
    private String originUrl;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 点击次数
     */
    private Long clickCount;

    /**
     * 最后访问时间
     */
    private String lastAccessTime;
}