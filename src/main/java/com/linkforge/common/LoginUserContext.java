package com.linkforge.common;

/**
 * 登录用户上下文。
 *
 * JWT 拦截器解析出 token 后写入当前线程的 userId（与 token 的 jti），
 * 业务层（创建 / 查询 / 删除短链接等）通过它拿到「当前登录用户」，
 * 从而实现「只能操作自己的短链接」。
 *
 * 使用 ThreadLocal，请求结束（afterCompletion）时必须 clear，
 * 避免 Tomcat 线程池复用导致的用户串号（A 的请求读到 B 的 userId）。
 */
public class LoginUserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> TOKEN_JTI = new ThreadLocal<>();

    public static void set(Long userId, String jti) {
        USER_ID.set(userId);
        TOKEN_JTI.set(jti);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static String getJti() {
        return TOKEN_JTI.get();
    }

    public static void clear() {
        USER_ID.remove();
        TOKEN_JTI.remove();
    }
}
