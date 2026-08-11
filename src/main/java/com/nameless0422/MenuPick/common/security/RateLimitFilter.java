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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Redis 기반 고정 윈도우 레이트 리밋 필터.
 *
 * <p>두 종류의 버킷을 관리한다.
 * <ul>
 *   <li>인증 경로(POST /api/v1/auth/**) — 미인증 요청이므로 클라이언트 IP 기준</li>
 *   <li>외부 API 프록시 경로(GET /api/v1/kakao/**, /api/v1/naver/**) — 인증 사용자 ID 기준.
 *       카카오/네이버 쿼터를 소모하는 경로라 개별 계정의 폭주를 막아야 한다.
 *       SecurityContext가 아직 비어 있으면 IP로 폴백한다.</li>
 *   <li>게스트 데모 픽(GET /api/v1/pick/demo) — permitAll이라 누구나 호출할 수 있으므로
 *       IP 기준. 프록시 버킷과 달리 사용자 ID로 묶을 수단이 아예 없다.</li>
 * </ul>
 *
 * <p><b>현재 필터 체인 순서 주의</b>: SecurityConfig가 이 필터와 JwtAuthenticationFilter를 모두
 * {@code addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)}로 등록하므로 둘의 order가
 * 같고, 등록 순서에 따라 <b>이 필터가 JwtAuthenticationFilter보다 먼저</b> 실행된다(실측 확인).
 * 즉 운영에서는 프록시 버킷이 사실상 IP 기준으로 동작한다.
 * SecurityConfig에서 이 필터를 JWT 필터 뒤로 옮기면 별도 코드 수정 없이 사용자 ID 기준으로 전환된다.
 *
 * <p>Redis 장애 시에는 요청을 통과시킨다(fail-open) — 레이트 리밋 때문에 서비스 전체가
 * 멈추는 편이 더 나쁘다는 판단.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    /** 인증 경로 버킷 — IP 기준. */
    private static final String AUTH_KEY_PREFIX = "rl:auth:";
    /** 외부 API 프록시 경로 버킷 — 사용자 ID(폴백 시 IP) 기준. */
    private static final String PROXY_KEY_PREFIX = "rl:proxy:";
    /** 게스트 데모 픽 버킷 — 미인증 경로라 IP 기준밖에 없다. */
    private static final String DEMO_KEY_PREFIX = "rl:demo:";

    private static final String DEMO_PICK_PATH = "/api/v1/pick/demo";

    private static final Set<String> AUTH_RATE_LIMITED_PATHS = Set.of(
            "/api/v1/auth/kakao",
            "/api/v1/auth/google",
            "/api/v1/auth/refresh"
    );

    private static final List<String> PROXY_RATE_LIMITED_PREFIXES = List.of(
            "/api/v1/kakao/",
            "/api/v1/naver/"
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

    /** 한 요청에 적용할 버킷(Redis 키 + 허용 횟수). */
    private record Bucket(String key, int limit) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        Bucket bucket = resolveBucket(request);
        if (bucket == null) {
            filterChain.doFilter(request, response);
            return;
        }

        int windowSeconds = rateLimitProperties.windowSeconds();

        Long count;
        try {
            count = redisTemplate.execute(RATE_LIMIT_SCRIPT, List.of(bucket.key()),
                    String.valueOf(windowSeconds));
        } catch (Exception e) {
            log.warn("Redis 장애로 rate limit 검사를 건너뜁니다: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (count != null && count > bucket.limit()) {
            writeTooManyRequests(response, windowSeconds);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletResponse response, int windowSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(windowSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.TOO_MANY_REQUESTS,
                        ErrorCode.TOO_MANY_REQUESTS.getMessage())));
    }

    /**
     * 요청에 적용할 버킷을 결정한다. 레이트 리밋 대상이 아니면 null.
     */
    private Bucket resolveBucket(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if ("POST".equalsIgnoreCase(request.getMethod()) && AUTH_RATE_LIMITED_PATHS.contains(uri)) {
            return new Bucket(AUTH_KEY_PREFIX + resolveClientIp(request),
                    rateLimitProperties.authLimitPerMinute());
        }

        if ("GET".equalsIgnoreCase(request.getMethod()) && DEMO_PICK_PATH.equals(uri)) {
            return new Bucket(DEMO_KEY_PREFIX + resolveClientIp(request),
                    rateLimitProperties.demoLimitPerMinute());
        }

        if ("GET".equalsIgnoreCase(request.getMethod()) && isProxyPath(uri)) {
            return new Bucket(PROXY_KEY_PREFIX + resolveProxySubject(request),
                    rateLimitProperties.proxyLimitPerMinute());
        }

        return null;
    }

    private boolean isProxyPath(String uri) {
        for (String prefix : PROXY_RATE_LIMITED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 프록시 경로의 버킷 주체. 인증된 사용자면 사용자 ID, 아니면 IP.
     * 두 네임스페이스가 섞이지 않도록 접두사를 붙인다.
     */
    private String resolveProxySubject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Long userId) {
            return "user:" + userId;
        }
        return "ip:" + resolveClientIp(request);
    }

    /**
     * 클라이언트 IP 해석.
     *
     * <p>X-Forwarded-For는 각 프록시가 "자신이 본 원격 주소"를 오른쪽에 덧붙인다.
     * 따라서 왼쪽 항목은 클라이언트가 임의로 채워 넣을 수 있고(첫 항목을 쓰면 레이트 리밋이
     * 헤더 위조 한 줄로 무력화된다), 오른쪽에서 신뢰 홉 수만큼 들어간 항목만이
     * 우리 프록시가 실제로 관측한 주소다.
     *
     * <p>항목 수가 신뢰 홉 수보다 적으면(= 기대한 프록시 체인을 통과하지 않은 요청이면)
     * 헤더를 믿지 않고 실제 연결 IP로 폴백한다.
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (rateLimitProperties.trustProxy()) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                String[] parts = xForwardedFor.split(",");
                int index = parts.length - rateLimitProperties.trustedProxyHops();
                if (index >= 0 && index < parts.length) {
                    String candidate = parts[index].trim();
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
                log.warn("X-Forwarded-For에서 신뢰 홉({}) 기준 항목을 찾지 못해 실제 연결 IP를 사용합니다 (항목 수={})",
                        rateLimitProperties.trustedProxyHops(), parts.length);
            }
        }
        return request.getRemoteAddr();
    }
}
