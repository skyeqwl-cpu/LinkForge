package com.linkforge.config;

import com.linkforge.interceptor.JwtInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册 JWT 拦截器。
 *
 * 拦截范围：所有 /api/** 接口都需要登录。
 * 放行：注册、登录（这两个接口本身不需要 token）。
 * 登出（/api/v1/auth/logout）不在此列，必须先带有效 token 才能登出。
 *
 * 公开跳转 /{shortCode} 不在 /api 前缀下，不会被拦截 —— 任何人都能访问短链接。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login"
                );
    }
}
