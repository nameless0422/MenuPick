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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Redis 기반 고정 윈도우 레이트 리밋 필터.
 *
 * <p>두 종류의 버킷을 관리한다.
 * <ul>
 *   <li>인증 경로(POST /api/v1/auth/**) — 미인증 요청이므로 클라이언트 IP 기준</li>
 *   <li>외부 API 프록시 경로(GET /api/v1/kakao/**) — 인증 사용자 ID 기준.
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

    /**
     * 제한 대상 판정은 <b>반드시</b> {@link PathPatternRequestMatcher}로 한다.
     * {@code request.getRequestURI()}를 문자열로 비교하면 안 된다.
     *
     * <p>이유: {@code getRequestURI()}는 서블릿 스펙상 <b>URL 디코딩되지 않은</b> 원본 경로를
     * 그대로 돌려준다(경로 파라미터 {@code ;k=v}도 붙어 있다). 반면 SecurityConfig의
     * {@code requestMatchers(...)}와 Spring MVC의 핸들러 매핑은 {@code PathPattern}이
     * 세그먼트를 <b>디코딩한 값</b>으로 매칭한다. 즉 두 판정 기준이 서로 다르다.
     *
     * <p>그 틈으로 레이트 리밋이 통째로 우회된다. {@code POST /api/v1/auth/%70assword-reset}은
     * {@code getRequestURI()} 기준으로는 목록에 없어 제한을 비껴가지만, permitAll과 핸들러 매핑은
     * 디코딩 후 {@code /api/v1/auth/password-reset}으로 보고 정상 처리한다 —
     * 임의 주소로 비밀번호 재설정 메일을 무제한 발송할 수 있게 되고(메일 폭탄), SMTP 쿼터가
     * 마르거나 발신 도메인이 스팸으로 등재되면 정상 가입자가 인증 메일을 못 받는다.
     * {@code /signup}·{@code /resend-verification}·{@code /pick/demo}도 마찬가지로 무제한이 된다.
     *
     * <p>Security가 쓰는 매처를 그대로 쓰면 두 기준이 정의상 어긋날 수 없다.
     * 이 필터는 DispatcherServlet보다 앞에서 도는 필터라 {@code ServletRequestPathUtils}가
     * 아직 경로를 파싱해 두지 않았을 수 있는데, {@code PathPatternRequestMatcher}는 그 경우
     * 스스로 파싱한 뒤 캐시를 되돌려 놓으므로(파싱 여부를 이쪽에서 따로 챙길 필요가 없다)
     * 필터 순서와 무관하게 안전하다.
     */
    private static final List<RequestMatcher> AUTH_RATE_LIMITED_MATCHERS = List.of(
            authMatcher("/api/v1/auth/kakao"),
            authMatcher("/api/v1/auth/google"),
            authMatcher("/api/v1/auth/refresh"),
            // 자체 계정 경로. 비밀번호 대입과 메일 폭탄(재발송·재설정 요청)이 모두 여기로 들어온다.
            // 계정 단위 제한은 LoginAttemptLimiter가 따로 담당한다 — IP 버킷만으로는
            // 여러 IP에서 한 계정을 노리는 분산 대입을 막지 못한다.
            authMatcher("/api/v1/auth/signup"),
            authMatcher("/api/v1/auth/login"),
            authMatcher("/api/v1/auth/verify-email"),
            authMatcher("/api/v1/auth/resend-verification"),
            authMatcher("/api/v1/auth/password-reset"),
            authMatcher("/api/v1/auth/password-reset/confirm"),
            // 동의 화면 리다이렉트. 인증 없이 열려 있고 호출마다 Redis를 건드리므로
            // 나머지 auth 경로와 같은 IP 버킷에 넣는다. GET이라 authMatcher(POST 고정)는 못 쓴다.
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/auth/kakao/authorize"),
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/auth/google/authorize"),
            // 소셜 계정 연동. 인증이 필요한 경로지만 호출당 카카오/구글 토큰 교환 + 프로필 조회로
            // 외부 API를 두 번 때리므로 제한 없이 두면 제공자 쿼터가 그대로 소모된다.
            // 사용자 ID가 아니라 IP 버킷이 되는데(이 필터가 JWT 필터보다 먼저 돈다 — 클래스 주석),
            // 연동은 계정당 몇 번 하고 마는 동작이라 IP 기준으로도 정상 사용자가 걸리지 않는다.
            // 해제(DELETE)는 외부 호출 없이 DB 행 하나를 지울 뿐이고 인증도 필요해,
            // 같은 성격의 /withdraw와 마찬가지로 넣지 않는다.
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/*/link"),
            // 로그아웃. AT가 만료된 뒤에도 동작해야 해서 permitAll이 됐고(SecurityConfig),
            // 그러면서 "인증이 필요하니 굳이 셀 것 없다"는 위 근거가 이 경로에서만 무너졌다.
            // 하는 일은 Redis 키 삭제 하나라 증폭은 없지만, 토큰 없이 부를 수 있는 auth 경로는
            // 전부 같은 IP 버킷에 넣는다는 규칙을 예외 없이 유지한다.
            PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/v1/auth/logout"),
            // 비밀번호 변경. 인증이 필요하지만 그것만으로는 부족하다.
            //  (1) currentPassword 대입: LoginAttemptLimiter는 login()에서만 집계하므로
            //      이 경로의 검증 실패는 어디에도 세어지지 않는다. 세션 하나만 손에 넣으면
            //      원래 비밀번호를 무제한으로 맞춰 볼 수 있고, 비밀번호 재사용을 감안하면
            //      피해가 이 서비스 밖으로 번진다.
            //  (2) Argon2 증폭: 요청마다 m=9216KiB·t=4 검증이 돌아, 톰캣 스레드 50개가
            //      전부 여기 들어가면 해싱 메모리만 450MB로 힙의 절반을 잡는다.
            //      SecurityConfig가 Argon2 파라미터를 고를 때 근거로 삼은 전제("로그인 경로는
            //      IP당 10회/분")가 이 경로에서만 무너져 있었다.
            // PATCH라 authMatcher(POST 고정)로는 잡히지 않는다.
            PathPatternRequestMatcher.pathPattern(HttpMethod.PATCH, "/api/v1/auth/password")
    );

    private static final RequestMatcher DEMO_PICK_MATCHER =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/pick/demo");

    private static final List<RequestMatcher> PROXY_RATE_LIMITED_MATCHERS = List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/v1/kakao/**")
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
     *
     * <p>경로 판정은 {@code getRequestURI()} 문자열 비교가 아니라 매처로 한다 —
     * 근거는 {@link #AUTH_RATE_LIMITED_MATCHERS} javadoc. HTTP 메서드 조건도 매처가 함께 들고 있다.
     */
    private Bucket resolveBucket(HttpServletRequest request) {
        if (matchesAny(AUTH_RATE_LIMITED_MATCHERS, request)) {
            return new Bucket(AUTH_KEY_PREFIX + resolveClientIp(request),
                    rateLimitProperties.authLimitPerMinute());
        }

        if (DEMO_PICK_MATCHER.matches(request)) {
            return new Bucket(DEMO_KEY_PREFIX + resolveClientIp(request),
                    rateLimitProperties.demoLimitPerMinute());
        }

        if (matchesAny(PROXY_RATE_LIMITED_MATCHERS, request)) {
            return new Bucket(PROXY_KEY_PREFIX + resolveProxySubject(request),
                    rateLimitProperties.proxyLimitPerMinute());
        }

        return null;
    }

    private static RequestMatcher authMatcher(String pattern) {
        return PathPatternRequestMatcher.pathPattern(HttpMethod.POST, pattern);
    }

    private boolean matchesAny(List<RequestMatcher> matchers, HttpServletRequest request) {
        for (RequestMatcher matcher : matchers) {
            if (matcher.matches(request)) {
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
