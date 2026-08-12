package com.linkforge.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShortLinkVO {
    private String shortCode;
    private String shortUrl;
    private String originUrl;
}