package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HistoryController.class)
class HistoryControllerTest extends AbstractControllerTest {

    @MockitoBean private HistoryService historyService;

    @Test
    @DisplayName("GET /api/v1/history/calendar - 월별 방문 기록 조회")
    void getVisitCalendar_success() throws Exception {
        var response = new HistoryResponse.VisitCalendarResponse(YearMonth.of(2026, 1), List.of(
                new HistoryResponse.VisitCalendarEntry(7L, "김치찌개", null,
                        LocalDateTime.of(2026, 1, 3, 12, 0))), false);
        given(historyService.getVisitCalendar(1L, "2026-01")).willReturn(response);

        mockMvc.perform(get("/api/v1/history/calendar").with(authentication(AUTH)).param("month", "2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.month").value("2026-01"))
                .andExpect(jsonPath("$.data.entries[0].id").value(7))
                .andExpect(jsonPath("$.data.entries[0].menuName").value("김치찌개"))
                .andExpect(jsonPath("$.data.entries[0].restaurantName").doesNotExist())
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/history/calendar - month 생략 전달")
    void getVisitCalendar_defaultMonth() throws Exception {
        given(historyService.getVisitCalendar(1L, null)).willReturn(
                new HistoryResponse.VisitCalendarResponse(YearMonth.of(2026, 1), List.of(), false));
        mockMvc.perform(get("/api/v1/history/calendar").with(authentication(AUTH)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.month").value("2026-01"));
        verify(historyService).getVisitCalendar(1L, null);
    }

    @Test
    @DisplayName("GET /api/v1/history/calendar - 형식 오류와 미래 월은 400")
    void getVisitCalendar_invalidMonth() throws Exception {
        given(historyService.getVisitCalendar(1L, "2026-1"))
                .willThrow(new BusinessException(ErrorCode.INVALID_INPUT));
        given(historyService.getVisitCalendar(1L, "2027-01"))
                .willThrow(new BusinessException(ErrorCode.INVALID_INPUT));
        mockMvc.perform(get("/api/v1/history/calendar").with(authentication(AUTH)).param("month", "2026-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
        mockMvc.perform(get("/api/v1/history/calendar").with(authentication(AUTH)).param("month", "2027-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/history/calendar - 미인증 시 401")
    void getVisitCalendar_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/history/calendar")).andExpect(status().isUnauthorized());
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
    @DisplayName("GET /api/v1/history - days=0이면 400 (@Min(1))")
    void getHistories_daysZero_badRequest() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                        .with(authentication(AUTH))
                        .param("days", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/history - days가 음수면 400 (@Min(1))")
    void getHistories_daysNegative_badRequest() throws Exception {
        mockMvc.perform(get("/api/v1/history")
                        .with(authentication(AUTH))
                        .param("days", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/v1/history/{id}/visit - 방문 처리 성공 (바디 없음)")
    void markVisited_success() throws Exception {
        mockMvc.perform(patch("/api/v1/history/1/visit")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(historyService).markVisited(1L, 1L, null);
    }

    @Test
    @DisplayName("PATCH /api/v1/history/{id}/visit - restaurantId 바디 전달")
    void markVisited_withRestaurantId() throws Exception {
        mockMvc.perform(patch("/api/v1/history/1/visit")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(historyService).markVisited(1L, 1L, 5L);
    }

    @Test
    @DisplayName("PATCH /api/v1/history/{id}/visit - 미존재 시 404")
    void markVisited_notFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.HISTORY_NOT_FOUND))
                .when(historyService).markVisited(1L, 99L, null);

        mockMvc.perform(patch("/api/v1/history/99/visit")
                        .with(authentication(AUTH)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/v1/history/{id}/feedback - 픽 피드백 기록")
    void recordFeedback_success() throws Exception {
        mockMvc.perform(patch("/api/v1/history/1/feedback")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"REJECTED\"}"))
                .andExpect(status().isOk());

        verify(historyService).recordFeedback(1L, 1L, RecommendationFeedback.REJECTED);
    }

    @Test
    @DisplayName("PATCH /api/v1/history/{id}/feedback - 잘못된 값은 400")
    void recordFeedback_invalidValue() throws Exception {
        mockMvc.perform(patch("/api/v1/history/1/feedback")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
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
