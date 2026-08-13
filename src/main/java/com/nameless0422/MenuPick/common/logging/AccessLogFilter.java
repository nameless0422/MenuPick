package com.nameless0422.MenuPick.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

/**
 * 요청 한 건당 한 줄의 접근 로그를 남긴다 (docs/Planning.md 7.3).
 *
 * <p>지금까지는 예외가 났을 때만 로그가 남아서, "어제 3시쯤 픽이 느렸다"는 제보에 대조할
 * 기록이 없었다. 정상 응답도 남겨야 사후에 무슨 일이 있었는지 재구성할 수 있다.
 *
 * <p><b>등록 위치</b>: {@link TraceIdFilter} 바로 안쪽이다. 바깥이면 traceId가 아직 MDC에
 * 없어 접근 로그만 상관관계에서 떨어져 나가고, Spring Security 체인(order -100)보다는
 * 바깥이어야 인증 실패(401)와 레이트 리밋(429)도 기록에 남는다. 그 둘이야말로
 * "왜 못 들어갔나"를 따질 때 가장 필요한 줄이다.
 *
 * <p><b>쿼리 문자열을 남기지 않는 이유</b>: 이 앱의 검색 경로({@code /api/v1/kakao/**})는
 * 사용자가 입력한 상호명·지역이 쿼리로 들어온다. 그대로 찍으면 "누가 무엇을 찾았는지"가
 * 로그에 쌓인다. 대신 파라미터 <b>이름만</b> 남긴다 — 페이지네이션이 깨졌을 때
 * {@code cursor}/{@code size}가 왔는지 같은 건 확인할 수 있으면서 내용은 새지 않는다.
 *
 * <p>클라이언트 IP도 남기지 않는다. 신뢰할 수 있는 IP 해석은 프록시 홉 수를 아는
 * {@code RateLimitFilter}에만 있고, 그 로직을 복제하면 두 곳이 갈라진다.
 */
@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

    /**
     * 이 시간을 넘으면 WARN으로 올린다.
     *
     * <p>성능 목표(docs/Planning.md 7.1)보다 넉넉히 잡았다. 촘촘하게 잡으면 평시에도 WARN이
     * 쌓여 진짜 이상징후가 묻힌다.
     */
    private static final long SLOW_REQUEST_MS = 1_000L;

    /**
     * 기록하지 않을 경로.
     *
     * <p>헬스체크는 Docker가 30초마다 때린다(하루 2,880건). 이 줄이 접근 로그의 대부분을
     * 차지하면 정작 사람이 만든 요청을 찾기 어려워지고, 컨테이너 로그 상한(20MB×5)도 빨리 찬다.
     */
    private static final Set<String> UNLOGGED_PATHS = Set.of(
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return UNLOGGED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 예외가 위로 튀어도 한 줄은 남긴다 — 실패한 요청일수록 기록이 필요하다.
            // 이때 상태 코드는 아직 에러 페이지로 확정되기 전 값일 수 있다.
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            write(request, response, elapsedMs);
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, long elapsedMs) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        int status = response.getStatus();
        String params = parameterNames(request);

        if (status >= 500) {
            // 스택 트레이스는 GlobalExceptionHandler가 ERROR로 이미 남겼다. 여기서는
            // 같은 traceId로 묶이는 접근 로그 한 줄만 더해 중복 ERROR를 만들지 않는다.
            log.warn("{} {} {} {}ms{}", method, uri, status, elapsedMs, params);
        } else if (elapsedMs >= SLOW_REQUEST_MS) {
            log.warn("{} {} {} {}ms{} (느린 요청)", method, uri, status, elapsedMs, params);
        } else {
            log.info("{} {} {} {}ms{}", method, uri, status, elapsedMs, params);
        }
    }

    /**
     * 파라미터 이름만 정렬해 돌려준다. 값은 담지 않는다.
     *
     * <p>정렬하는 이유는 같은 엔드포인트의 줄이 파라미터 순서 때문에 달라 보이지 않게 하기 위한 것이다.
     */
    private String parameterNames(HttpServletRequest request) {
        Set<String> names = new TreeSet<>(request.getParameterMap().keySet());
        return names.isEmpty() ? "" : " params=" + names;
    }
}
