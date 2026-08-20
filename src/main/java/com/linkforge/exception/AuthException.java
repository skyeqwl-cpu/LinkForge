package com.linkforge.exception;

import lombok.Getter;

/**
 * 认证异常：未登录 / token 无效或过期 / 已被登出。统一映射为 HTTP 401。
 */
@Getter
public class AuthException extends RuntimeException {

    private final Integer code;

    public AuthException(String message) {
        super(message);
        this.code = 401;
    }
}
