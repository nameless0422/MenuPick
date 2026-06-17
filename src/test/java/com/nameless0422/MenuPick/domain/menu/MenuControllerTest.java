package com.nameless0422.MenuPick.domain.menu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MenuController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class MenuControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private MenuService menuService;
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
    @DisplayName("GET /api/v1/menus - 메뉴 목록 조회 성공")
    void getMenus_success() throws Exception {
        var summary = new MenuResponse.MenuSummary(1L, "김치찌개", 3, false, Set.of("한식"), List.of());
        var response = new MenuResponse.MenuListResponse(List.of(summary), null, false);
        given(menuService.getMenus(1L, null, 20)).willReturn(response);

        mockMvc.perform(get("/api/v1/menus")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.menus[0].name").value("김치찌개"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/menus - 미인증 시 401")
    void getMenus_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/menus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/menus - 메뉴 생성 성공 (201)")
    void createMenu_success() throws Exception {
        var detail = new MenuResponse.MenuDetail(
                1L, "된장찌개", "집밥", 2, false, Set.of("한식"),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        given(menuService.createMenu(eq(1L), any(MenuRequest.Create.class))).willReturn(detail);

        mockMvc.perform(post("/api/v1/menus")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MenuRequest.Create("된장찌개", "집밥", 2, Set.of("한식"), null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("된장찌개"));
    }

    @Test
    @DisplayName("POST /api/v1/menus - 이름 누락 시 400")
    void createMenu_invalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/menus")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MenuRequest.Create("", "", 1, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/menus/{menuId} - 상세 조회 성공")
    void getMenu_success() throws Exception {
        var detail = new MenuResponse.MenuDetail(
                1L, "김치찌개", "맛있음", 3, false, Set.of(),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        given(menuService.getMenu(1L, 1L)).willReturn(detail);

        mockMvc.perform(get("/api/v1/menus/1")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("김치찌개"));
    }

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId} - 수정 성공")
    void updateMenu_success() throws Exception {
        var detail = new MenuResponse.MenuDetail(
                1L, "수정됨", "메모", 5, true, Set.of("양식"),
                List.of(), LocalDateTime.now(), LocalDateTime.now());
        given(menuService.updateMenu(eq(1L), eq(1L), any(MenuRequest.Update.class))).willReturn(detail);

        mockMvc.perform(put("/api/v1/menus/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MenuRequest.Update("수정됨", "메모", 5, true, Set.of("양식"), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수정됨"));
    }

    @Test
    @DisplayName("DELETE /api/v1/menus/{menuId} - 삭제 성공")
    void deleteMenu_success() throws Exception {
        mockMvc.perform(delete("/api/v1/menus/1")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(menuService).deleteMenu(1L, 1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/menus/{menuId} - 미인증 시 401")
    void deleteMenu_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/menus/1"))
                .andExpect(status().isUnauthorized());
    }
}
