package com.linkforge.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShortLinkStatsVO {

    /**
     * 短链接编码
     */
    private String shortCode;

    /**
     * 原始长链接
     */
    private String originUrl;

    /**
     * 点击次数
     */
    private Long clickCount;

    /**
     * 最后访问时间
     */
    private String lastAccessTime;
}