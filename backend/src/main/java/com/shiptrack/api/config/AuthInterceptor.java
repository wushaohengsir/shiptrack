package com.shiptrack.api.config;

import com.shiptrack.api.error.ApiException;
import com.shiptrack.contract.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Bearer token check for the public API (§1.2). Fails closed when no token is
 * configured, so a forgotten deployment secret never opens the service.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final ApiProperties properties;

    public AuthInterceptor(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String expected = properties.getAccessToken();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (expected.isBlank() || header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "缺少或无效的访问令牌");
        }
        if (!constantTimeEquals(expected, header.substring("Bearer ".length()).trim())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "缺少或无效的访问令牌");
        }
        return true;
    }

    static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
