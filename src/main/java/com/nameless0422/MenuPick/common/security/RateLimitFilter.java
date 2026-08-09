package com.nameless0422.MenuPick.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private static final String KEY_PREFIX = "rate_limit:";
    private static final Set<String> RATE_LIMITED_PATHS = Set.of(
            "/api/v1/auth/kakao",
            "/api/v1/auth/google",
            "/api/v1/auth/refresh"
    );

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties rateLimitProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!isRateLimitedPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Long count;
        try {
            String key = KEY_PREFIX + resolveClientIp(request);
            count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(key), "60");
        } catch (Exception e) {
            log.warn("Redis 장애로 rate limit 검사를 건너뜁니다: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (count != null && count > MAX_REQUESTS_PER_MINUTE) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.error(ErrorCode.TOO_MANY_REQUESTS,
                            ErrorCode.TOO_MANY_REQUESTS.getMessage())));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (rateLimitProperties.trustProxy()) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                return xForwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private boolean isRateLimitedPath(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && RATE_LIMITED_PATHS.contains(request.getRequestURI());
    }
}
