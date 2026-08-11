package com.nameless0422.MenuPick.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 상관관계 식별자(traceId)를 MDC에 심고 응답 헤더로 되돌려주는 필터.
 *
 * <p>같은 요청에서 나온 로그 줄들을 시간대 추정이 아니라 식별자로 묶기 위한 것이다.
 * 응답 헤더 {@code X-Request-Id}로 같은 값을 내보내므로, 클라이언트/프록시 로그와도 대조할 수 있다.
 *
 * <p><b>등록 방식</b>: 이 클래스에는 의도적으로 {@code @Component}를 붙이지 않는다.
 * 이 프로젝트의 다른 필터들({@code JwtAuthenticationFilter}, {@code RateLimitFilter})은 빈으로
 * 등록되어 있어 Boot의 서블릿 필터 자동 등록과 SecurityConfig의 명시 등록이 함께 걸린다.
 * traceId는 인증 실패(401)·레이트 리밋(429)까지 포함해 전 구간을 묶어야 하므로 체인 최바깥에
 * 와야 하고, 그러려면 {@code Ordered.HIGHEST_PRECEDENCE}로 순서를 못 박은 명시 등록이 필요하다.
 * 필터 빈이 되면 자동 등록이 같이 걸려 등록 경로가 둘로 갈리므로, 인스턴스 생성과 등록을 모두
 * {@link TraceIdFilterConfig}가 전담한다.
 *
 * <p><b>한계</b>: MDC는 ThreadLocal이라 이 값은 요청을 처리하는 스레드에만 유효하다.
 * {@code @Async}/스케줄러 스레드로는 전파되지 않는다.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** 인바운드로 받고 응답으로 되돌려주는 상관관계 헤더. */
    public static final String TRACE_ID_HEADER = "X-Request-Id";

    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** 인증 성공 시 {@code JwtAuthenticationFilter}가 채운다. 정리는 여기서도 한 번 더 쓸어담는다. */
    public static final String USER_ID_MDC_KEY = "userId";

    /**
     * 인바운드 헤더 화이트리스트.
     *
     * <p>헤더 값은 클라이언트가 임의로 채우는 입력이다. 개행이나 제어문자가 섞인 값을 그대로 MDC에
     * 넣으면 로그 한 줄이 여러 줄로 쪼개지면서 가짜 로그 줄(가짜 스택트레이스, 가짜 에러)을 주입할 수
     * 있고, 로그 수집기 파싱도 깨진다. 길이 제한은 로그 줄 폭주 방지용.
     * 어긋나는 값은 거부하고 새로 생성한다 — 상관관계가 끊길 뿐 요청 자체는 실패시킬 이유가 없다.
     */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        // 응답이 커밋되기 전에 넣어야 한다. 하위 필터가 401/429를 바로 써버릴 수 있다.
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 서블릿 컨테이너는 스레드를 재사용한다. 정리를 빠뜨리면 다음 요청 로그에 남의 traceId가 붙는다.
            // 체인 최바깥이므로 하위에서 채운 키까지 여기서 한 번 더 쓸어담는다.
            MDC.remove(TRACE_ID_MDC_KEY);
            MDC.remove(USER_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String inbound = request.getHeader(TRACE_ID_HEADER);
        if (inbound != null && SAFE_TRACE_ID.matcher(inbound).matches()) {
            return inbound;
        }
        return generateTraceId();
    }

    /** 로그 줄이 길어지지 않도록 UUID 앞 16자리(64비트)만 쓴다 — 상관관계 용도에는 충분하다. */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
