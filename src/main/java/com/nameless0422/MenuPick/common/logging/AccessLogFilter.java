package com.nameless0422.MenuPick.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    /** 한 줄에 싣는 파라미터 이름의 최대 개수. 넘는 만큼은 개수로만 적는다. */
    static final int MAX_LOGGED_PARAM_NAMES = 20;

    /** 이름 하나의 최대 길이. 이 서비스의 실제 파라미터 이름은 전부 이보다 훨씬 짧다. */
    static final int MAX_PARAM_NAME_LENGTH = 40;

    /**
     * 파라미터 이름만 정렬해 돌려준다. 값은 담지 않는다.
     *
     * <p>정렬하는 이유는 같은 엔드포인트의 줄이 파라미터 순서 때문에 달라 보이지 않게 하기 위한 것이다.
     *
     * <h2>이름도 사용자 입력이다</h2>
     *
     * <p>값을 안 찍으니 안전하다고 보기 쉽지만, <b>이름도 요청을 보낸 쪽이 정한다.</b>
     * 인증이 필요 없고(이 필터는 시큐리티보다 먼저 돈다) URL 디코딩까지 끝난 문자열이다.
     * 그래서 두 가지를 여기서 묶는다.
     *
     * <ul>
     *   <li><b>폭주</b> — 폼 본문의 파라미터도 여기 잡힌다. 이름만 수천 개 채운 요청 하나가
     *       수 MB짜리 로그 한 줄이 되고, journal은 상한(1GB)에 닿으면 오래된 것부터 지우므로
     *       그게 곧 <b>진짜 로그를 밀어내는 수단</b>이 된다. 개수와 길이를 자른다.</li>
     *   <li><b>위조</b> — 줄바꿈이 든 이름으로 가짜 로그 줄을 만드는 것은 레이아웃이 막는다
     *       ({@link CrlfSafeMessageConverter}). 여기서 따로 씻지 않는 이유는 한 곳에서 막아야
     *       다른 로그 문장도 같이 보호받기 때문이다.</li>
     * </ul>
     *
     * <p>잘린 사실은 감추지 않는다({@code …}, {@code +N more}) — 조사하는 사람이 "이 요청은
     * 정상이 아니었다"를 한눈에 알아야 한다.
     */
    private String parameterNames(HttpServletRequest request) {
        Set<String> names = new TreeSet<>(request.getParameterMap().keySet());
        if (names.isEmpty()) {
            return "";
        }
        List<String> shown = new ArrayList<>(Math.min(names.size(), MAX_LOGGED_PARAM_NAMES));
        for (String name : names) {
            if (shown.size() == MAX_LOGGED_PARAM_NAMES) {
                break;
            }
            shown.add(name.length() <= MAX_PARAM_NAME_LENGTH
                    ? name
                    : name.substring(0, MAX_PARAM_NAME_LENGTH) + "…");
        }
        int hidden = names.size() - shown.size();
        return " params=" + shown + (hidden > 0 ? " +" + hidden + " more" : "");
    }
}
