package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MenuRestaurantController.class)
class MenuRestaurantControllerTest extends AbstractControllerTest {

    @MockitoBean private MenuRestaurantService menuRestaurantService;

    private static MenuRestaurantResponse.MenuRestaurantDetail detail(Integer rating, String memo) {
        return new MenuRestaurantResponse.MenuRestaurantDetail(
                1L, 10L, "진주회관", "서울시 중구", rating, memo,
                LocalDateTime.now(), LocalDateTime.now());
    }

    // --- 목록 조회 ---

    @Test
    @DisplayName("GET /api/v1/menus/{menuId}/restaurants - 연결된 식당 목록 조회 성공")
    void getMenuRestaurants_success() throws Exception {
        given(menuRestaurantService.getMenuRestaurants(1L, 1L))
                .willReturn(new MenuRestaurantResponse.MenuRestaurantListResponse(
                        List.of(detail(4, "국물이 진하다"))));

        mockMvc.perform(get("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.menuRestaurants[0].restaurantId").value(10))
                .andExpect(jsonPath("$.data.menuRestaurants[0].restaurantName").value("진주회관"))
                .andExpect(jsonPath("$.data.menuRestaurants[0].rating").value(4));
    }

    @Test
    @DisplayName("GET /api/v1/menus/{menuId}/restaurants - 연결이 없으면 빈 배열")
    void getMenuRestaurants_empty() throws Exception {
        given(menuRestaurantService.getMenuRestaurants(1L, 1L))
                .willReturn(new MenuRestaurantResponse.MenuRestaurantListResponse(List.of()));

        mockMvc.perform(get("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuRestaurants").isEmpty());
    }

    @Test
    @DisplayName("GET /api/v1/menus/{menuId}/restaurants - 타인의 메뉴는 404")
    void getMenuRestaurants_menuNotFound() throws Exception {
        given(menuRestaurantService.getMenuRestaurants(1L, 99L))
                .willThrow(new BusinessException(ErrorCode.MENU_NOT_FOUND));

        mockMvc.perform(get("/api/v1/menus/99/restaurants")
                        .with(authentication(AUTH)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/menus/{menuId}/restaurants - 미인증 시 401")
    void getMenuRestaurants_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/menus/1/restaurants"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(menuRestaurantService);
    }

    // --- 연결 생성 ---

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - 연결 생성 성공 (201)")
    void createMenuRestaurant_success() throws Exception {
        given(menuRestaurantService.createMenuRestaurant(
                eq(1L), eq(1L), any(MenuRestaurantRequest.Create.class)))
                .willReturn(detail(5, "인생 맛집"));

        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MenuRestaurantRequest.Create(10L, 5, "인생 맛집"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.restaurantId").value(10))
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.memo").value("인생 맛집"));
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - rating은 선택 값이라 null이어도 201")
    void createMenuRestaurant_nullRating_success() throws Exception {
        given(menuRestaurantService.createMenuRestaurant(
                eq(1L), eq(1L), any(MenuRestaurantRequest.Create.class)))
                .willReturn(detail(null, null));

        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rating").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - 이미 연결된 식당이면 409")
    void createMenuRestaurant_duplicate_conflict() throws Exception {
        given(menuRestaurantService.createMenuRestaurant(
                eq(1L), eq(1L), any(MenuRestaurantRequest.Create.class)))
                .willThrow(new BusinessException(ErrorCode.MENU_RESTAURANT_DUPLICATE));

        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - 존재하지 않는 메뉴면 404")
    void createMenuRestaurant_menuNotFound() throws Exception {
        given(menuRestaurantService.createMenuRestaurant(
                eq(1L), eq(99L), any(MenuRestaurantRequest.Create.class)))
                .willThrow(new BusinessException(ErrorCode.MENU_NOT_FOUND));

        mockMvc.perform(post("/api/v1/menus/99/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":10}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - 존재하지 않는(또는 타인의) 식당이면 404")
    void createMenuRestaurant_restaurantNotFound() throws Exception {
        given(menuRestaurantService.createMenuRestaurant(
                eq(1L), eq(1L), any(MenuRestaurantRequest.Create.class)))
                .willThrow(new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));

        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - restaurantId 누락 시 400")
    void createMenuRestaurant_missingRestaurantId_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("restaurantId"));

        verifyNoInteractions(menuRestaurantService);
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - rating이 0이면 400 (@Min(1))")
    void createMenuRestaurant_ratingTooLow_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":10,\"rating\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rating"));

        verifyNoInteractions(menuRestaurantService);
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - rating이 6이면 400 (@Max(5))")
    void createMenuRestaurant_ratingTooHigh_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":10,\"rating\":6}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rating"));

        verifyNoInteractions(menuRestaurantService);
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - 미인증 시 401")
    void createMenuRestaurant_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":10}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(menuRestaurantService);
    }

    // --- 연결 수정 ---

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId}/restaurants/{restaurantId} - 수정 성공")
    void updateMenuRestaurant_success() throws Exception {
        given(menuRestaurantService.updateMenuRestaurant(
                eq(1L), eq(1L), eq(10L), any(MenuRestaurantRequest.Update.class)))
                .willReturn(detail(2, "생각보다 별로"));

        mockMvc.perform(put("/api/v1/menus/1/restaurants/10")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MenuRestaurantRequest.Update(2, "생각보다 별로"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(2))
                .andExpect(jsonPath("$.data.memo").value("생각보다 별로"));
    }

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId}/restaurants/{restaurantId} - rating 범위를 벗어나면 400")
    void updateMenuRestaurant_ratingOutOfRange_badRequest() throws Exception {
        mockMvc.perform(put("/api/v1/menus/1/restaurants/10")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rating"));

        verifyNoInteractions(menuRestaurantService);
    }

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId}/restaurants/{restaurantId} - 연결이 없으면 404")
    void updateMenuRestaurant_linkNotFound() throws Exception {
        given(menuRestaurantService.updateMenuRestaurant(
                eq(1L), eq(1L), eq(99L), any(MenuRestaurantRequest.Update.class)))
                .willThrow(new BusinessException(ErrorCode.MENU_RESTAURANT_NOT_FOUND));

        mockMvc.perform(put("/api/v1/menus/1/restaurants/99")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/menus/{menuId}/restaurants - 1000자를 넘는 메모는 400"
            + " (TEXT 컬럼까지 내려가 409가 되기 전에 막는다)")
    void createMenuRestaurant_tooLongMemo_badRequest() throws Exception {
        String longMemo = "가".repeat(1001);
        mockMvc.perform(post("/api/v1/menus/1/restaurants")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restaurantId\":10,\"rating\":4,\"memo\":\"" + longMemo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("memo"));

        verifyNoInteractions(menuRestaurantService);
    }

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId}/restaurants/{restaurantId} - 1000자를 넘는 메모는 400")
    void updateMenuRestaurant_tooLongMemo_badRequest() throws Exception {
        String longMemo = "가".repeat(1001);
        mockMvc.perform(put("/api/v1/menus/1/restaurants/10")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":4,\"memo\":\"" + longMemo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("memo"));

        verifyNoInteractions(menuRestaurantService);
    }

    // --- 연결 삭제 ---

    @Test
    @DisplayName("DELETE /api/v1/menus/{menuId}/restaurants/{restaurantId} - 삭제 성공")
    void deleteMenuRestaurant_success() throws Exception {
        mockMvc.perform(delete("/api/v1/menus/1/restaurants/10")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(menuRestaurantService).deleteMenuRestaurant(1L, 1L, 10L);
    }

    @Test
    @DisplayName("DELETE /api/v1/menus/{menuId}/restaurants/{restaurantId} - 연결이 없으면 404")
    void deleteMenuRestaurant_linkNotFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.MENU_RESTAURANT_NOT_FOUND))
                .when(menuRestaurantService).deleteMenuRestaurant(1L, 1L, 99L);

        mockMvc.perform(delete("/api/v1/menus/1/restaurants/99")
                        .with(authentication(AUTH)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/menus/{menuId}/restaurants/{restaurantId} - 미인증 시 401")
    void deleteMenuRestaurant_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/menus/1/restaurants/10"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(menuRestaurantService);
    }
}
