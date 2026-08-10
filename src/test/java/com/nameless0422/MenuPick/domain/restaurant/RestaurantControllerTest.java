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
                        new RestaurantResponse.RestaurantSummary(1L, "진주회관", "서울시 중구")));

        mockMvc.perform(get("/api/v1/restaurants")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("진주회관"));
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
                null, null, LocalDateTime.now(), LocalDateTime.now());
        given(restaurantService.createRestaurant(eq(1L), any(RestaurantRequest.Create.class)))
                .willReturn(detail);

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

    @Test
    @DisplayName("GET /api/v1/restaurants/{id} - 상세 조회 성공")
    void getRestaurant_success() throws Exception {
        var detail = new RestaurantResponse.RestaurantDetail(
                1L, "진주회관", "서울시 중구", "02-1234-5678",
                new BigDecimal("37.5665350"), new BigDecimal("126.9779692"),
                "https://naver.me/abc", "12345",
                LocalDateTime.now(), LocalDateTime.now());
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
                null, null, LocalDateTime.now(), LocalDateTime.now());
        given(restaurantService.updateRestaurant(eq(1L), eq(1L), any(RestaurantRequest.Update.class)))
                .willReturn(detail);

        mockMvc.perform(put("/api/v1/restaurants/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RestaurantRequest.Update("수정됨", "새 주소", "010-9999",
                                        new BigDecimal("37.5"), new BigDecimal("127.0"),
                                        null, null))))
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
