package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.domain.trend.dto.PickTrendResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 집단 통계 API의 HTTP 계약 — 경계와, 비어 있을 때 무엇을 함께 주는가. */
@WebMvcTest(PickTrendController.class)
class PickTrendControllerTest extends AbstractControllerTest {

    @MockitoBean private PickTrendService pickTrendService;

    @Test
    @DisplayName("GET /trends - 순위와 함께 기간·최소 인원을 준다")
    void trends() throws Exception {
        given(pickTrendService.currentTrends()).willReturn(new PickTrendResponse(
                List.of(new PickTrendResponse.Entry("한식", 12, 1),
                        new PickTrendResponse.Entry("일식", 7, 2)),
                List.of(new PickTrendResponse.Entry("김치찌개", 6, 1)),
                PickTrendResponse.Status.READY, 7, 5, 10, LocalDateTime.of(2026, 9, 20, 4, 10)));

        mockMvc.perform(get("/api/v1/trends").with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[0].label").value("한식"))
                .andExpect(jsonPath("$.data.categories[0].userCount").value(12))
                .andExpect(jsonPath("$.data.menus[0].label").value("김치찌개"))
                .andExpect(jsonPath("$.data.windowDays").value(7))
                .andExpect(jsonPath("$.data.minUsers").value(5));
    }

    /**
     * 빈 배열만 주면 화면이 "인기 메뉴가 없다"와 "기능이 안 돈다"를 구분할 수 없다.
     * 이유를 말할 재료({@code minUsers}·{@code windowDays})가 빈 응답에도 실려야 한다.
     */
    @Test
    @DisplayName("표본이 없어도 기간·최소 인원은 준다 — 화면이 이유를 말할 수 있게")
    void emptyStillCarriesThreshold() throws Exception {
        given(pickTrendService.currentTrends())
                .willReturn(new PickTrendResponse(List.of(), List.of(), PickTrendResponse.Status.READY,
                        7, 5, 10, LocalDateTime.of(2026, 9, 20, 4, 10)));

        mockMvc.perform(get("/api/v1/trends").with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isEmpty())
                .andExpect(jsonPath("$.data.minUsers").value(5))
                // computedAt은 null이면 직렬화에서 빠진다(ApiResponse의 NON_NULL은 래퍼에만
                // 걸리지만, 이 필드는 record라 값 자체가 null로 나간다). 지어내지 않는 것이 요점이다.
                .andExpect(jsonPath("$.data.computedAt").value("2026-09-20T04:10:00"));
    }

    /**
     * 개인정보가 없는 집계값이라 공개해도 안전하지만, 공개하면 가입하지 않은 쪽에서 반복
     * 수집할 수 있다. 이 값은 서비스 규모의 하한을 드러내는 지표이기도 하다.
     */
    @Test
    @DisplayName("인증 없이는 닿지 않는다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/trends"))
                .andExpect(status().isUnauthorized());
    }
}
