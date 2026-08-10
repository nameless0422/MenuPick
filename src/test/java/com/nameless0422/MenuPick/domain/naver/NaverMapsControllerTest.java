package com.nameless0422.MenuPick.domain.naver;

import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import com.nameless0422.MenuPick.domain.naver.dto.NaverMapsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NaverMapsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class NaverMapsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private NaverMapsClient naverMapsClient;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private StringRedisTemplate redisTemplate;

    private static final UsernamePasswordAuthenticationToken AUTH =
            new UsernamePasswordAuthenticationToken(1L, null, List.of());

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.increment(any())).willReturn(1L);
    }

    @Test
    @DisplayName("GET /api/v1/naver/geocode - 주소 검색 성공")
    void geocode_success() throws Exception {
        var result = new NaverMapsResponse.GeocodeResult(
                "OK",
                new NaverMapsResponse.GeocodeResult.Meta(1, 1, 1),
                List.of(new NaverMapsResponse.GeocodeResult.Address(
                        "서울특별시 중구 세종대로 110", "서울특별시 중구 태평로1가 31",
                        "126.9783882", "37.5666103", "0.0")));

        given(naverMapsClient.geocode(eq("서울시청"), any(), any())).willReturn(result);

        mockMvc.perform(get("/api/v1/naver/geocode")
                        .param("query", "서울시청")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.addresses[0].roadAddress").value("서울특별시 중구 세종대로 110"))
                .andExpect(jsonPath("$.data.addresses[0].x").value("126.9783882"));
    }

    @Test
    @DisplayName("GET /api/v1/naver/geocode - query 누락 시 400")
    void geocode_missingQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/naver/geocode")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/naver/geocode - 미인증 시 401")
    void geocode_unauthorized_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/naver/geocode")
                        .param("query", "서울시청"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/naver/reverse-geocode - 좌표 검색 성공")
    void reverseGeocode_success() throws Exception {
        var result = new NaverMapsResponse.ReverseGeocodeResult(
                new NaverMapsResponse.ReverseGeocodeResult.Status(0, "ok", "done"),
                List.of());

        given(naverMapsClient.reverseGeocode(eq("126.978,37.566"), any())).willReturn(result);

        mockMvc.perform(get("/api/v1/naver/reverse-geocode")
                        .param("coords", "126.978,37.566")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status.code").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/naver/reverse-geocode - coords 누락 시 400")
    void reverseGeocode_missingCoords_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/naver/reverse-geocode")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/naver/reverse-geocode - 미인증 시 401")
    void reverseGeocode_unauthorized_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/naver/reverse-geocode")
                        .param("coords", "126.978,37.566"))
                .andExpect(status().isUnauthorized());
    }

    // --- 파라미터 선검증 (이슈 #8) ---

    @Test
    @DisplayName("GET /api/v1/naver/geocode - query가 100자를 넘으면 업스트림 호출 없이 400")
    void geocode_queryTooLong_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/naver/geocode")
                        .param("query", "가".repeat(101))
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(naverMapsClient);
    }

    @Test
    @DisplayName("GET /api/v1/naver/geocode - count가 100을 넘으면 400")
    void geocode_countOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/naver/geocode")
                        .param("query", "서울시청")
                        .param("count", "1000")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(naverMapsClient);
    }

    // --- 프록시 경로 레이트 리밋 (이슈 #10) ---

    @Test
    @DisplayName("GET /api/v1/naver/geocode - 분당 한도를 넘으면 429 + Retry-After")
    @SuppressWarnings("unchecked")
    void geocode_rateLimited_returns429() throws Exception {
        // SecurityConfig가 RateLimitFilter를 JwtAuthenticationFilter보다 앞에 등록하므로
        // 이 필터 시점에는 SecurityContext가 비어 있고, 설계대로 IP 키로 폴백한다.
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:proxy:ip:127.0.0.1")), eq("60")))
                .willReturn(31L);

        mockMvc.perform(get("/api/v1/naver/geocode")
                        .param("query", "서울시청")
                        .with(authentication(AUTH)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        verifyNoInteractions(naverMapsClient);
    }
}
