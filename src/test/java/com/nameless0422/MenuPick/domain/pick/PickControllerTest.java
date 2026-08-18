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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PickController.class)
class PickControllerTest extends AbstractControllerTest {

    @MockitoBean private PickService pickService;
    @MockitoBean private DemoPickService demoPickService;

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

    // --- 필터 컬렉션 검증 ---
    //
    // 여기서 막지 않으면 픽 자체는 성공한 뒤 히스토리 INSERT(filter_value VARCHAR(100))에서
    // 죽어 픽 전체가 롤백되고 409가 나간다. 사용자에겐 정상 요청인데 원인 불명 오류가 된다.

    @Test
    @DisplayName("POST /api/v1/pick - 20자를 넘는 카테고리가 섞여 있으면 400 (히스토리 INSERT 전에 막는다)")
    void pick_tooLongCategory_badRequest() throws Exception {
        // filter_value는 VARCHAR(100). 매칭되는 "한식"이 함께 있어 픽은 성공하고
        // 히스토리 저장에서만 터지던 조합이다.
        String longCategory = "가".repeat(150);
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[\"한식\",\"" + longCategory + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(pickService);
    }

    @Test
    @DisplayName("POST /api/v1/pick - 공백 카테고리는 400")
    void pick_blankCategory_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[\"   \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/pick - 카테고리가 20개를 넘으면 400")
    void pick_tooManyCategories_badRequest() throws Exception {
        String categories = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "\"cat" + i + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[" + categories + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categories"));
    }

    @Test
    @DisplayName("POST /api/v1/pick - 포함 태그가 20개를 넘으면 400")
    void pick_tooManyTagIds_badRequest() throws Exception {
        String tagIds = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\":[" + tagIds + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("tagIds"));
    }

    @Test
    @DisplayName("POST /api/v1/pick - 제외 태그가 20개를 넘으면 400")
    void pick_tooManyExcludeTagIds_badRequest() throws Exception {
        String tagIds = java.util.stream.IntStream.rangeClosed(1, 21)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludeTagIds\":[" + tagIds + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("excludeTagIds"));
    }

    @Test
    @DisplayName("POST /api/v1/pick - 상한(20개, 20자) 안쪽 요청은 통과")
    void pick_atLimit_success() throws Exception {
        var menuDetail = new MenuResponse.MenuDetail(
                3L, "비빔밥", null, 1, false, Set.of("한식"),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        given(pickService.pick(eq(1L), any())).willReturn(
                new PickResponse.PickResult(3L, menuDetail, List.of()));

        String categories = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "\"cat" + i + "\"")
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[" + categories + "]}"))
                .andExpect(status().isOk());
    }
}
