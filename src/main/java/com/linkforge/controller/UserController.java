package com.linkforge.controller;

import com.linkforge.common.Result;
import com.linkforge.dto.UserLoginDTO;
import com.linkforge.dto.UserRegisterDTO;
import com.linkforge.service.UserService;
import com.linkforge.vo.LoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * 注册（成功后自动登录，直接返回 token）
     */
    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody UserRegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    /**
     * 登录：返回 JWT token
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody UserLoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    /**
     * 登出：需要携带有效 token，服务端将其 jti 拉黑
     */
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            userService.logout(header.substring(7).trim());
        }
        return Result.success("登出成功");
    }
}
