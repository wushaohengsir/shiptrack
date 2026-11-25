package com.shiptrack.api.config;

import com.shiptrack.api.error.ApiException;
import com.shiptrack.contract.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code X-Internal-Token} check for {@code /internal/**} (§5.22/5.23/5.24).
 * Never accepts the public Bearer token.
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final ApiProperties properties;

    public InternalAuthInterceptor(ApiProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = properties.getInternalToken();
        String actual = request.getHeader("X-Internal-Token");
        if (expected.isBlank() || actual == null
                || !AuthInterceptor.constantTimeEquals(expected, actual.trim())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "缺少或无效的内部令牌");
        }
        return true;
    }
}
