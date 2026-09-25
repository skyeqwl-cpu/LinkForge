package com.linkforge.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShortLinkVO {
    private String shortCode;
    private String shortUrl;
    private String originUrl;
    private String expireTime;
    /** 访问类型：0=无限制，1=仅创建者 */
    private Integer accessType;
}