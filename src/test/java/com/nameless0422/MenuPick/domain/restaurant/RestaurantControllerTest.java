package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RestaurantController.class)
class RestaurantControllerTest extends AbstractControllerTest {

    @MockitoBean private RestaurantService restaurantService;

    @Test
    @DisplayName("GET /api/v1/restaurants - 목록 조회 성공")
    void getRestaurants_success() throws Exception {
        given(restaurantService.getRestaurants(1L))
                .willReturn(List.of(
                        new RestaurantResponse.RestaurantSummary(
                                1L, "진주회관", "서울시 중구",
                                new BigDecimal("37.5665350"), new BigDecimal("126.9779692"))));

        mockMvc.perform(get("/api/v1/restaurants")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("진주회관"))
                // 목록에서 지도 마커를 찍으므로 좌표가 빠지면 마커가 통째로 사라진다
                .andExpect(jsonPath("$.data[0].latitude").value(37.566535))
                .andExpect(jsonPath("$.data[0].longitude").value(126.9779692));
    }

    @Test
    @DisplayName("GET /api/v1/restaurants - 미인증 시 401")
    void getRestaurants_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/restaurants"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/restaurants - 생성 성공 (201)")
    void createRestaurant_success() throws Exception {
        var detail = new RestaurantResponse.RestaurantDetail(
                1L, "새 식당", "주소", "010-1234",
                new BigDecimal("37.5665350"), new BigDecimal("126.9779692"),
                null, null, LocalDateTime.now(), LocalDateTime.now(), 0L);
        given(restaurantService.createRestaurant(eq(1L), any(RestaurantRequest.Create.class)))
                .willReturn(new RestaurantService.CreateResult(detail, true));

        mockMvc.perform(post("/api/v1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Create("새 식당", "주소", "010-1234",
                                        new BigDecimal("37.5665350"), new BigDecimal("126.9779692"),
                                        null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("새 식당"));
    }

    @Test
    @DisplayName("POST /api/v1/restaurants - 이미 저장한 장소면 200 (409 아님 — 실패가 아니다)")
    void createRestaurant_alreadySaved() throws Exception {
        var detail = new RestaurantResponse.RestaurantDetail(
                1L, "진주회관", "주소", null,
                new BigDecimal("37.5"), new BigDecimal("127.0"),
                null, "8005012", LocalDateTime.now(), LocalDateTime.now(), 0L);
        given(restaurantService.createRestaurant(eq(1L), any(RestaurantRequest.Create.class)))
                .willReturn(new RestaurantService.CreateResult(detail, false));

        // 사용자는 "이 가게를 목록에 두고 싶다"고 말했고 그 상태는 이미 참이다. 실패로 돌려주면
        // 아무 문제도 없는데 오류 화면을 보게 된다. 201로 뭉뚱그리면 프론트가 안내를 달리 못 한다.
        mockMvc.perform(post("/api/v1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Create("진주회관", "주소", null,
                                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                                        null, "8005012"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/restaurants - 이름 누락 시 400")
    void createRestaurant_invalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Create("", null, null,
                                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                                        null, null))))
                .andExpect(status().isBadRequest());
    }

    /**
     * naverUrl은 화면에서 그대로 {@code <a href>}가 된다. 길이만 보고 통과시키면
     * {@code javascript:}를 저장해 자기 브라우저에서 스크립트를 돌릴 수 있고(self-XSS),
     * 공유·추천처럼 남의 식당이 내 화면에 그려지는 기능이 붙는 순간 저장형 XSS가 된다.
     */
    @Test
    @DisplayName("POST /api/v1/restaurants - naverUrl이 javascript: 스킴이면 400")
    void createRestaurant_javascriptUrl_rejected() throws Exception {
        mockMvc.perform(post("/api/v1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Create("진주회관", null, null,
                                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                                        "javascript:alert(document.cookie)", null))))
                .andExpect(status().isBadRequest());

        verify(restaurantService, never()).createRestaurant(any(), any());
    }

    @Test
    @DisplayName("PUT /api/v1/restaurants/{id} - 수정에서도 스킴을 본다")
    void updateRestaurant_javascriptUrl_rejected() throws Exception {
        // 생성만 막으면 만들고 나서 고치는 경로로 그대로 들어온다.
        mockMvc.perform(put("/api/v1/restaurants/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Update("진주회관", null, null,
                                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                                        "javascript:alert(1)", 0L))))
                .andExpect(status().isBadRequest());

        verify(restaurantService, never()).updateRestaurant(any(), any(), any());
    }

    @Test
    @DisplayName("POST /api/v1/restaurants - 공백을 끼워 스킴을 감춰도 400")
    void createRestaurant_whitespaceObfuscatedUrl_rejected() throws Exception {
        // 브라우저는 href를 읽을 때 스킴 안의 탭·개행을 지우고 javascript로 정규화한다.
        mockMvc.perform(post("/api/v1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Create("진주회관", null, null,
                                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                                        "java\tscript:alert(1)", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/restaurants - 링크 없음(null·빈 문자열)은 그대로 통과한다")
    void createRestaurant_blankUrl_allowed() throws Exception {
        // 화면은 "지도 링크 없음"을 빈 입력으로 보낸다. @Pattern은 null만 건너뛰므로
        // 빈 문자열을 함께 허용해 두지 않으면 링크 없는 저장이 전부 400이 된다.
        var created = new RestaurantResponse.RestaurantDetail(
                1L, "진주회관", null, null,
                new BigDecimal("37.5"), new BigDecimal("127.0"),
                null, null, LocalDateTime.now(), LocalDateTime.now(), 0L);
        given(restaurantService.createRestaurant(eq(1L), any(RestaurantRequest.Create.class)))
                .willReturn(new RestaurantService.CreateResult(created, true));

        mockMvc.perform(post("/api/v1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Create("진주회관", null, null,
                                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                                        "", null))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("GET /api/v1/restaurants/{id} - 상세 조회 성공")
    void getRestaurant_success() throws Exception {
        var detail = new RestaurantResponse.RestaurantDetail(
                1L, "진주회관", "서울시 중구", "02-1234-5678",
                new BigDecimal("37.5665350"), new BigDecimal("126.9779692"),
                "https://naver.me/abc", "12345",
                LocalDateTime.now(), LocalDateTime.now(), 0L);
        given(restaurantService.getRestaurant(1L, 1L)).willReturn(detail);

        mockMvc.perform(get("/api/v1/restaurants/1")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("진주회관"));
    }

    @Test
    @DisplayName("PUT /api/v1/restaurants/{id} - 수정 성공")
    void updateRestaurant_success() throws Exception {
        var detail = new RestaurantResponse.RestaurantDetail(
                1L, "수정됨", "새 주소", "010-9999",
                new BigDecimal("37.5"), new BigDecimal("127.0"),
                null, null, LocalDateTime.now(), LocalDateTime.now(), 0L);
        given(restaurantService.updateRestaurant(eq(1L), eq(1L), any(RestaurantRequest.Update.class)))
                .willReturn(detail);

        mockMvc.perform(put("/api/v1/restaurants/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Update("수정됨", "새 주소", "010-9999",
                                        new BigDecimal("37.5"), new BigDecimal("127.0"), null, 0L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수정됨"));
    }

    @Test
    @DisplayName("DELETE /api/v1/restaurants/{id} - 삭제 성공")
    void deleteRestaurant_success() throws Exception {
        mockMvc.perform(delete("/api/v1/restaurants/1")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(restaurantService).deleteRestaurant(1L, 1L);
    }
}
