package com.linkforge.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 短链接管理视图对象
 * 用于「查询单个 / 列表 / 分页」等管理类接口
 * 注意：createTime 统一序列化为字符串（yyyy-MM-dd HH:mm:ss），
 * 与统计接口里 LocalDateTime 的 ISO 格式区分开。
 */
@Data
@Builder
public class ShortLinkManageVO {

    /**
     * 短码
     */
    private String shortCode;

    /**
     * 完整短链接（域名 + 短码），如 http://localhost:8081/2
     */
    private String shortUrl;

    /**
     * 原始长链接
     */
    private String originUrl;

    /**
     * 创建时间（格式：yyyy-MM-dd HH:mm:ss）
     */
    private String createTime;

    /**
     * 过期时间（格式：yyyy-MM-dd HH:mm:ss），NULL 表示长期有效
     */
    private String expireTime;

    /**
     * 点击次数
     */
    private Long clickCount;

    /**
     * 访问类型：0=无限制，1=仅创建者
     */
    private Integer accessType;
}
