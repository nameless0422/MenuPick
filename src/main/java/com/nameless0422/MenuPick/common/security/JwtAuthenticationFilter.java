package com.nameless0422.MenuPick.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.logging.TraceIdFilter;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Access Token 인증.
 *
 * <p>서명·만료·종류에 더해 토큰의 {@code ver}가 계정의 현재 {@code auth_version}과 같은지 본다.
 * 로그아웃·탈퇴·비밀번호 변경 후에도 토큰이 만료까지 통하던 창을 닫으려면 요청마다 서버 상태를
 * 봐야 한다. 지금 규모에서는 PK 한 건 조회로 충분해 Redis 블랙리스트를 따로 두지 않는다.
 *
 * <p>DB 조회가 실패하면 통과시키지 않고 503을 준다. 어차피 대부분의 API가 DB 없이는 응답할 수
 * 없고, 401을 주면 프론트가 멀쩡한 세션을 로그아웃시킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // 토큰은 한 번만 파싱한다 — 검증과 userId 추출이 같은 Claims를 공유한다.
        // 파싱 실패·만료·무효화는 인증만 비워둔 채 체인을 계속 태우고,
        // 최종 401 응답은 CustomAuthenticationEntryPoint가 만든다 (예외 유출로 인한 500 방지).
        Optional<Claims> claims = jwtTokenProvider.parseAccessToken(resolveToken(request));
        Long userId = claims.map(jwtTokenProvider::getUserId).orElse(null);

        if (userId != null) {
            boolean current;
            try {
                long tokenVersion = jwtTokenProvider.getAuthVersion(claims.get());
                current = userRepository.findActiveAuthVersion(userId)
                        .filter(version -> version == tokenVersion)
                        .isPresent();
            } catch (DataAccessException | TransactionException e) {
                log.error("인증 버전 조회 실패: {}", e.getMessage());
                writeUnavailable(response);
                return;
            }

            if (current) {
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put(TraceIdFilter.USER_ID_MDC_KEY, String.valueOf(userId));
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 서블릿 스레드는 재사용되므로 정리를 빠뜨리면 다음 요청 로그에 남의 userId가 붙는다.
            // 키가 없을 때 remove는 무해하므로 인증 여부와 무관하게 무조건 지운다.
            MDC.remove(TraceIdFilter.USER_ID_MDC_KEY);
        }
    }

    private void writeUnavailable(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.AUTH_CHECK_UNAVAILABLE;
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(code, code.getMessage())));
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
