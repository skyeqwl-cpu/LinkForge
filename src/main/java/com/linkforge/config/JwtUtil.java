package com.linkforge.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具（jjwt 0.12.x 风格 API）。
 *
 * 设计要点：
 * - subject 存放 userId（字符串），解析后转 Long 即可定位用户，无需再查库。
 * - 每个 token 带一个随机 jti，用于「登出黑名单」：登出时把 jti 写进 Redis，
 *   拦截器每次校验 token 前先看黑名单，从而实现真正的服务端登出（JWT 本身无状态）。
 * - 密钥来自配置文件 jwt.secret（HS256 要求 >= 32 字节），固定不变，保证重启后仍可校验旧 token。
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration; // 毫秒

    private SecretKey getKey() {
        // HS256 需要至少 256 bit（32 字节）的密钥
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 token
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(exp)
                .id(UUID.randomUUID().toString())
                .signWith(getKey())
                .compact();
    }

    public Long parseUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    public Date getExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
