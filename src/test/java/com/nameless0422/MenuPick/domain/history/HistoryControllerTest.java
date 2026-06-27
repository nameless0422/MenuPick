package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HistoryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class HistoryControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HistoryService historyService;
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
    @DisplayName("GET /api/v1/history - 히스토리 목록 조회 성공")
    void getHistories_success() throws Exception {
        var summary = new HistoryResponse.HistorySummary(
                1L, "김치찌개", "맛집A", false,
                LocalDateTime.of(2026, 6, 28, 12, 0), null,
                List.of(new HistoryResponse.FilterCondition("CATEGORY", "한식")));
        var response = new HistoryResponse.HistoryListResponse(List.of(summary), null, false);

        given(historyService.getHistories(1L, null, null, 20)).willReturn(response);

        mockMvc.perform(get("/api/v1/history")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.histories[0].menuName").value("김치찌개"))
                .andExpect(jsonPath("$.data.histories[0].filterConditions[0].filterType").value("CATEGORY"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/history - 미인증 시 401")
    void getHistories_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/history - days 파라미터 전달")
    void getHistories_withDays() throws Exception {
        var response = new HistoryResponse.HistoryListResponse(List.of(), null, false);
        given(historyService.getHistories(1L, null, 30, 10)).willReturn(response);

        mockMvc.perform(get("/api/v1/history")
                        .with(authentication(AUTH))
                        .param("days", "30")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.histories").isEmpty());
    }

    @Test
    @DisplayName("PATCH /api/v1/history/{id}/visit - 방문 처리 성공")
    void markVisited_success() throws Exception {
        mockMvc.perform(patch("/api/v1/history/1/visit")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(historyService).markVisited(1L, 1L);
    }

    @Test
    @DisplayName("PATCH /api/v1/history/{id}/visit - 미존재 시 404")
    void markVisited_notFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.HISTORY_NOT_FOUND))
                .when(historyService).markVisited(1L, 99L);

        mockMvc.perform(patch("/api/v1/history/99/visit")
                        .with(authentication(AUTH)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/history/{id} - 삭제 성공")
    void deleteHistory_success() throws Exception {
        mockMvc.perform(delete("/api/v1/history/1")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(historyService).deleteHistory(1L, 1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/history/{id} - 미인증 시 401")
    void deleteHistory_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/history/1"))
                .andExpect(status().isUnauthorized());
    }
}
