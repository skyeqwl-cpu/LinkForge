package com.linkforge.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginVO {

    /**
     * JWT 令牌，前端后续请求放在 Authorization: Bearer <token>
     */
    private String token;

    private Long userId;

    private String username;
}
