package com.linkforge.interceptor;

import com.linkforge.common.LoginUserContext;
import com.linkforge.config.JwtUtil;
import com.linkforge.exception.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 拦截器：
 * 1. 从 Authorization: Bearer <token> 取出 token；
 * 2. 解析 userId（无效/过期直接抛 AuthException → 401）；
 * 3. 校验 Redis 登出黑名单（jti）；
 * 4. 写入 LoginUserContext，供业务层做「只能操作自己的数据」。
 *
 * 注册见 config/WebConfig：拦截所有 /api/**，放行 register / login。
 * 注意：公开跳转接口 /{shortCode} 不在 /api 下，天然不被拦截，任何人都能访问短链。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // 1. 取 Authorization 头
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            throw new AuthException("请先登录");
        }
        String token = header.substring(7).trim();

        // 2. 解析（无效 / 过期都会抛异常 → 401）
        Long userId;
        String jti;
        try {
            userId = jwtUtil.parseUserId(token);
            jti = jwtUtil.getJti(token);
        } catch (Exception e) {
            throw new AuthException("登录失效，请重新登录");
        }

        // 3. 登出黑名单校验
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(BLACKLIST_PREFIX + jti))) {
            throw new AuthException("登录已失效，请重新登录");
        }

        // 4. 写入上下文
        LoginUserContext.set(userId, jti);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // 请求结束清理 ThreadLocal，防止线程复用串号
        LoginUserContext.clear();
    }
}
