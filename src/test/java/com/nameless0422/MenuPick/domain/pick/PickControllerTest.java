package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PickController.class)
class PickControllerTest extends AbstractControllerTest {

    @MockitoBean private PickService pickService;

    @Test
    @DisplayName("POST /api/v1/pick - 랜덤 픽 성공")
    void pick_success() throws Exception {
        var menuDetail = new MenuResponse.MenuDetail(
                1L, "김치찌개", "맛있음", 3, false, Set.of("한식"),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        var restaurant = new PickResponse.RestaurantWithDistance(1L, "식당A", "서울시 강남구", 500.0);
        var result = new PickResponse.PickResult(1L, menuDetail, List.of(restaurant));

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
    @DisplayName("POST /api/v1/pick - body 없이도 동작 (필터 없이 전체 픽, @Valid는 스킵됨)")
    void pick_withoutBody() throws Exception {
        var menuDetail = new MenuResponse.MenuDetail(
                2L, "돈까스", null, 1, false, Set.of("일식"),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        var result = new PickResponse.PickResult(2L, menuDetail, List.of());

        given(pickService.pick(eq(1L), any())).willReturn(result);

        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menu.name").value("돈까스"));
    }

    // --- 요청 검증 (이슈 #6) ---

    @Test
    @DisplayName("POST /api/v1/pick - 위도가 범위를 벗어나면 400")
    void pick_latitudeOutOfRange_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 91.0, \"longitude\": 126.9784}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("latitude"));
    }

    @Test
    @DisplayName("POST /api/v1/pick - 경도가 범위를 벗어나면 400")
    void pick_longitudeOutOfRange_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 37.5666, \"longitude\": -180.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("longitude"));
    }

    @Test
    @DisplayName("POST /api/v1/pick - maxDistance가 0 이하면 400")
    void pick_nonPositiveMaxDistance_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxDistance\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("maxDistance"));
    }
}
