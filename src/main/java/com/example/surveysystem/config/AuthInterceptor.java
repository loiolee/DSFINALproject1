package com.example.surveysystem.config;

import com.example.surveysystem.exception.BusinessException;
import com.example.surveysystem.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录令牌拦截器：验证 Authorization 中的 Bearer JWT，并把用户 ID 写入请求属性。
 * 全项目只有这一处认证拦截（由 WebMvcConfig 注册），发证与验签统一使用 JwtUtil。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    public static final String USER_ID_ATTRIBUTE = "authenticatedUserId";
    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 防御性放行预检请求；正常情况下预检已被 CORS 机制短路，不会到达这里。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            throw BusinessException.unauthorized("请先登录，并在 Authorization 中提供 Bearer Token");
        }
        String token = header.substring("Bearer ".length()).trim();
        String userId = jwtUtil.verify(token)
                .orElseThrow(() -> BusinessException.unauthorized("登录令牌无效或已过期，请重新登录"));
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        return true;
    }
}
