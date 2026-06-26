package com.nameless0422.MenuPick.domain.pick;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PickController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class PickControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private PickService pickService;
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
    @DisplayName("POST /api/v1/pick - 랜덤 픽 성공")
    void pick_success() throws Exception {
        var menuDetail = new MenuResponse.MenuDetail(
                1L, "김치찌개", "맛있음", 3, false, Set.of("한식"),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        var restaurant = new PickResponse.RestaurantWithDistance(1L, "식당A", "서울시 강남구", 500.0);
        var result = new PickResponse.PickResult(menuDetail, List.of(restaurant));

        given(pickService.pick(eq(1L), any(PickRequest.class))).willReturn(result);

        var request = new PickRequest(
                Set.of("한식"), null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), 1000);

        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.menu.name").value("김치찌개"))
                .andExpect(jsonPath("$.data.restaurants[0].name").value("식당A"));
    }

    @Test
    @DisplayName("POST /api/v1/pick - 미인증 시 401")
    void pick_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/pick - 후보 없으면 404")
    void pick_noCandidates() throws Exception {
        given(pickService.pick(eq(1L), any()))
                .willThrow(new BusinessException(ErrorCode.NO_PICK_CANDIDATES));

        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/pick - body 없이도 동작 (필터 없이 전체 픽)")
    void pick_withoutBody() throws Exception {
        var menuDetail = new MenuResponse.MenuDetail(
                2L, "돈까스", null, 1, false, Set.of("일식"),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        var result = new PickResponse.PickResult(menuDetail, List.of());

        given(pickService.pick(eq(1L), any())).willReturn(result);

        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menu.name").value("돈까스"));
    }
}
