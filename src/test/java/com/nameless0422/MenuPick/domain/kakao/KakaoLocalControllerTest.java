package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
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

@WebMvcTest(KakaoLocalController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class KakaoLocalControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private KakaoLocalClient kakaoLocalClient;
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
    @DisplayName("GET /api/v1/kakao/search/keyword - 키워드 검색 성공")
    void searchByKeyword_success() throws Exception {
        var result = new KakaoLocalResponse.PlaceSearchResult(
                new KakaoLocalResponse.PlaceSearchResult.Meta(1, 1, true),
                List.of(new KakaoLocalResponse.PlaceSearchResult.Place(
                        "진주회관", "서울 중구 충무로1가 24-11", "서울 중구 명동9길 12",
                        "126.985302", "37.561251", "02-776-3525",
                        "http://place.map.kakao.com/8005012",
                        "음식점 > 한식", "FD6", "음식점", "")));

        given(kakaoLocalClient.searchByKeyword(eq("진주회관"), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(result);

        mockMvc.perform(get("/api/v1/kakao/search/keyword")
                        .param("query", "진주회관")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.documents[0].place_name").value("진주회관"));
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/keyword - query 누락 시 400")
    void searchByKeyword_missingQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/keyword")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/keyword - 미인증 시 401")
    void searchByKeyword_unauthorized_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/keyword")
                        .param("query", "진주회관"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/category - 카테고리 검색 성공")
    void searchByCategory_success() throws Exception {
        var result = new KakaoLocalResponse.PlaceSearchResult(
                new KakaoLocalResponse.PlaceSearchResult.Meta(0, 0, true),
                List.of());

        given(kakaoLocalClient.searchByCategory(eq("FD6"), eq("126.98"), eq("37.56"), any(), any(), any(), any()))
                .willReturn(result);

        mockMvc.perform(get("/api/v1/kakao/search/category")
                        .param("categoryGroupCode", "FD6")
                        .param("x", "126.98")
                        .param("y", "37.56")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/category - categoryGroupCode 누락 시 400")
    void searchByCategory_missingCode_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/category")
                        .param("x", "126.98")
                        .param("y", "37.56")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/category - 미인증 시 401")
    void searchByCategory_unauthorized_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/category")
                        .param("categoryGroupCode", "FD6")
                        .param("x", "126.98")
                        .param("y", "37.56"))
                .andExpect(status().isUnauthorized());
    }

    // --- 파라미터 선검증 (이슈 #8) ---

    @Test
    @DisplayName("GET /api/v1/kakao/search/keyword - query가 100자를 넘으면 업스트림 호출 없이 400")
    void searchByKeyword_queryTooLong_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/keyword")
                        .param("query", "가".repeat(101))
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kakaoLocalClient);
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/keyword - size가 카카오 허용 범위(1~15)를 벗어나면 400")
    void searchByKeyword_sizeOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/keyword")
                        .param("query", "맛집")
                        .param("size", "100")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kakaoLocalClient);
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/keyword - radius가 20000을 넘으면 400")
    void searchByKeyword_radiusOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/keyword")
                        .param("query", "맛집")
                        .param("radius", "50000")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kakaoLocalClient);
    }

    @Test
    @DisplayName("GET /api/v1/kakao/search/category - page가 45를 넘으면 400")
    void searchByCategory_pageOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/kakao/search/category")
                        .param("categoryGroupCode", "FD6")
                        .param("x", "126.98")
                        .param("y", "37.56")
                        .param("page", "46")
                        .with(authentication(AUTH)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kakaoLocalClient);
    }

    // --- 프록시 경로 레이트 리밋 (이슈 #10) ---

    @Test
    @DisplayName("GET /api/v1/kakao/search/keyword - 분당 한도를 넘으면 429 + Retry-After")
    @SuppressWarnings("unchecked")
    void searchByKeyword_rateLimited_returns429() throws Exception {
        // SecurityConfig가 RateLimitFilter를 JwtAuthenticationFilter보다 앞에 등록하므로
        // 이 필터 시점에는 SecurityContext가 비어 있고, 설계대로 IP 키로 폴백한다.
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:proxy:ip:127.0.0.1")), eq("60")))
                .willReturn(31L);

        mockMvc.perform(get("/api/v1/kakao/search/keyword")
                        .param("query", "맛집")
                        .with(authentication(AUTH)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        verifyNoInteractions(kakaoLocalClient);
    }
}
