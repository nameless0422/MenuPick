package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MenuController.class)
class MenuControllerTest extends AbstractControllerTest {

    @MockitoBean private MenuService menuService;

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

    // --- 요청 검증 (이슈 #6, #7) ---

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId} - isExcluded 누락 시 400 (이슈 #7 — 조용히 false로 풀리지 않는다)")
    void updateMenu_missingIsExcluded_badRequest() throws Exception {
        mockMvc.perform(put("/api/v1/menus/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정됨\",\"memo\":\"메모\",\"weight\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("isExcluded"));
    }

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId} - isExcluded가 명시적 null이어도 400")
    void updateMenu_nullIsExcluded_badRequest() throws Exception {
        mockMvc.perform(put("/api/v1/menus/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정됨\",\"memo\":\"메모\",\"weight\":5,\"isExcluded\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("isExcluded"));
    }

    @Test
    @DisplayName("POST /api/v1/menus - 공백 카테고리는 400")
    void createMenu_blankCategory_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/menus")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"된장찌개\",\"weight\":1,\"categories\":[\"   \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/menus - 20자를 넘는 카테고리는 400")
    void createMenu_tooLongCategory_badRequest() throws Exception {
        String longCategory = "가".repeat(21);
        mockMvc.perform(post("/api/v1/menus")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"된장찌개\",\"weight\":1,\"categories\":[\"" + longCategory + "\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/menus - 1000자를 넘는 메모는 400 (TEXT 컬럼까지 내려가 409가 되기 전에 막는다)")
    void createMenu_tooLongMemo_badRequest() throws Exception {
        String longMemo = "가".repeat(1001);
        mockMvc.perform(post("/api/v1/menus")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"된장찌개\",\"weight\":1,\"memo\":\"" + longMemo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("memo"));
    }

    @Test
    @DisplayName("PUT /api/v1/menus/{menuId} - 1000자를 넘는 메모는 400")
    void updateMenu_tooLongMemo_badRequest() throws Exception {
        String longMemo = "가".repeat(1001);
        mockMvc.perform(put("/api/v1/menus/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정됨\",\"weight\":3,\"isExcluded\":false,\"memo\":\""
                                + longMemo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("memo"));
    }

    @Test
    @DisplayName("PATCH /api/v1/menus/weights - menuId가 null이면 400")
    void batchUpdateWeight_nullMenuId_badRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/menus/weights")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"menuId\":null,\"weight\":3}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("PATCH /api/v1/menus/weights - 항목의 weight가 범위를 벗어나면 400 (@Valid 캐스케이드)")
    void batchUpdateWeight_weightOutOfRange_badRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/menus/weights")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"menuId\":1,\"weight\":9}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
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

    // --- 일괄 가중치 수정 ---

    @Test
    @DisplayName("PATCH /api/v1/menus/weights - 일괄 가중치 수정 성공")
    void batchUpdateWeight_success() throws Exception {
        var request = new MenuRequest.BatchUpdateWeight(
                List.of(new MenuRequest.WeightEntry(1L, 5), new MenuRequest.WeightEntry(2L, 3)));

        mockMvc.perform(patch("/api/v1/menus/weights")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(menuService).batchUpdateWeight(eq(1L), any(MenuRequest.BatchUpdateWeight.class));
    }

    @Test
    @DisplayName("PATCH /api/v1/menus/weights - 미인증 시 401")
    void batchUpdateWeight_unauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/menus/weights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // --- 제외 목록 조회 ---

    @Test
    @DisplayName("GET /api/v1/menus/excluded - 제외 목록 조회 성공")
    void getExcludedMenus_success() throws Exception {
        var summary = new MenuResponse.MenuSummary(1L, "제외메뉴", 1, true, Set.of("한식"), List.of());
        given(menuService.getExcludedMenus(1L)).willReturn(List.of(summary));

        mockMvc.perform(get("/api/v1/menus/excluded")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("제외메뉴"))
                .andExpect(jsonPath("$.data[0].isExcluded").value(true));
    }

    // --- 제외 토글 ---

    @Test
    @DisplayName("PATCH /api/v1/menus/{menuId}/exclude - 제외 처리 성공")
    void toggleExclude_success() throws Exception {
        mockMvc.perform(patch("/api/v1/menus/1/exclude")
                        .with(authentication(AUTH))
                        .param("exclude", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(menuService).toggleExclude(1L, 1L, true);
    }

    @Test
    @DisplayName("PATCH /api/v1/menus/{menuId}/exclude - 미인증 시 401")
    void toggleExclude_unauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/menus/1/exclude")
                        .param("exclude", "true"))
                .andExpect(status().isUnauthorized());
    }

    // --- 컬렉션 상한·null 원소 (#87) ---

    /**
     * PickRequest는 같은 문제를 인지하고 세 집합 전부에 상한을 걸어 뒀는데 MenuRequest에는
     * 없었다. 상한이 없으면 categories에 5만 개를 담은 요청 하나가 한 트랜잭션에서
     * menu_categories에 5만 행을 INSERT한다.
     */
    @Test
    @DisplayName("POST /api/v1/menus - 카테고리가 상한을 넘으면 400")
    void createMenu_tooManyCategories() throws Exception {
        Set<String> categories = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "카테고리" + i)
                .collect(Collectors.toSet());

        mockMvc.perform(post("/api/v1/menus")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MenuRequest.Create("된장찌개", "", 1, categories, null))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(menuService);
    }

    @Test
    @DisplayName("PUT /api/v1/menus/{id} - 태그가 상한을 넘으면 400")
    void updateMenu_tooManyTags() throws Exception {
        Set<Long> tagIds = LongStream.rangeClosed(1, 21).boxed().collect(Collectors.toSet());

        mockMvc.perform(put("/api/v1/menus/1")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MenuRequest.Update("된장찌개", "", 1, false, null, tagIds))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(menuService);
    }

    /**
     * {@code @Valid}는 null 원소를 검증 대상에서 제외한다. 원소에 {@code @NotNull}이 없으면
     * [null]이 그대로 서비스까지 내려가 entry.menuId()에서 NPE를 내고, catch-all이 500 +
     * 풀 스택트레이스를 남긴다. 잘못된 요청은 400이어야 한다.
     */
    @Test
    @DisplayName("PATCH /api/v1/menus/weights - 목록에 null 원소가 있으면 500이 아니라 400")
    void batchUpdateWeight_nullEntry() throws Exception {
        mockMvc.perform(patch("/api/v1/menus/weights")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[null]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(menuService);
    }

}
