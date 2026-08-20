package com.linkforge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.config.JwtUtil;
import com.linkforge.dto.UserLoginDTO;
import com.linkforge.dto.UserRegisterDTO;
import com.linkforge.entity.User;
import com.linkforge.exception.AuthException;
import com.linkforge.exception.BizException;
import com.linkforge.mapper.UserMapper;
import com.linkforge.vo.LoginVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // BCrypt：自带随机盐，每次加密结果不同，但 matches 能正确校验
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    /**
     * 注册：校验用户名唯一 → BCrypt 加密 → 落库 → 自动登录返回 token
     */
    @Transactional
    public LoginVO register(UserRegisterDTO dto) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getUsername())
        );
        if (count != null && count > 0) {
            throw new BizException(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword())); // 存密文，绝不存明文
        userMapper.insert(user);

        // 注册成功直接返回 token，前端无需再调一次登录
        String token = jwtUtil.generateToken(user.getId());
        return LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }

    /**
     * 登录：校验用户名 / 密码 → 返回 token
     */
    public LoginVO login(UserLoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, dto.getUsername())
        );
        // 用户名不存在或密码不匹配，统一报「用户名或密码错误」，避免泄露哪个错了
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new AuthException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId());
        return LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }

    /**
     * 登出：把当前 token 的 jti 写入 Redis 黑名单，TTL = token 剩余有效期。
     * 拦截器每次校验 token 前会查黑名单，命中即视为已登出。
     */
    public void logout(String token) {
        String jti = jwtUtil.getJti(token);
        Date exp = jwtUtil.getExpiration(token);
        long ttlMillis = exp.getTime() - System.currentTimeMillis();
        if (ttlMillis > 0) {
            stringRedisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + jti,
                    "1",
                    ttlMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }
}
