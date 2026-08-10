package com.nameless0422.MenuPick.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * {@code @WebMvcTest} 슬라이스 공용 베이스.
 *
 * <p>컨트롤러 슬라이스는 자동설정 대상이 웹 계층으로 좁혀지므로, 실제 요청이 통과해야 하는
 * 보안 필터 체인(SecurityConfig + JWT/레이트리밋 필터 + 401/403 핸들러)을 명시적으로 import하고
 * 그 협력 빈(JwtTokenProvider, StringRedisTemplate)을 목으로 채워야 한다.
 * 이 구성이 모든 컨트롤러 테스트에 동일하게 복붙되어 있었기에 여기로 모았다.
 *
 * <p>목 {@link StringRedisTemplate}은 별도 스텁이 필요 없다. {@link RateLimitFilter}는
 * {@code execute(RedisScript, ...)}로 카운터를 올리는데, 스텁하지 않은 목은 null을 반환하고
 * 필터는 null을 "카운트 불명 → 통과"로 처리하기 때문이다(fail-open).
 * 429 경로를 검증하려면 개별 테스트에서 {@code execute}만 스텁하면 된다
 * (예: {@code KakaoLocalControllerTest}, {@code NaverMapsControllerTest}).
 *
 * <p>하위 클래스는 {@code @WebMvcTest(XxxController.class)}와 대상 서비스의
 * {@code @MockitoBean}만 선언하면 된다.
 */
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
public abstract class AbstractControllerTest {

    /** 인증된 사용자(userId=1)를 흉내내는 인증 객체 — {@code authentication(AUTH)}로 사용한다. */
    protected static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken(1L, null, List.of());

    @Autowired protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean protected JwtTokenProvider jwtTokenProvider;
    @MockitoBean protected StringRedisTemplate redisTemplate;
}
