package com.nameless0422.MenuPick.common.security;

import com.nameless0422.MenuPick.common.logging.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 토큰은 한 번만 파싱한다 — 검증과 userId 추출이 같은 Claims를 공유한다.
        // 파싱 실패·만료는 빈 Optional로 돌아오므로 인증만 비워둔 채 체인을 계속 태우고,
        // 최종 401 응답은 CustomAuthenticationEntryPoint가 만든다 (예외 유출로 인한 500 방지).
        jwtTokenProvider.parseAccessToken(resolveToken(request))
                .map(jwtTokenProvider::getUserId)
                .ifPresent(userId -> {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(userId, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    MDC.put(TraceIdFilter.USER_ID_MDC_KEY, String.valueOf(userId));
                });

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 서블릿 스레드는 재사용되므로 정리를 빠뜨리면 다음 요청 로그에 남의 userId가 붙는다.
            // 키가 없을 때 remove는 무해하므로 인증 여부와 무관하게 무조건 지운다.
            MDC.remove(TraceIdFilter.USER_ID_MDC_KEY);
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
