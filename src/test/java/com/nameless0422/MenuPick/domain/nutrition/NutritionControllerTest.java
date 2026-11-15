package com.nameless0422.MenuPick.domain.nutrition;

import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NutritionController.class)
class NutritionControllerTest extends AbstractControllerTest {
    @MockitoBean private NutritionService nutritionService;

    @Test void searchRequiresAuthenticationAndBoundsQueryAndSize() throws Exception {
        given(nutritionService.search("김치", 10)).willReturn(List.of(
                new NutritionDto.FoodReferenceSummary(1L, "김치찌개", "찌개", null, 196, null, null, null)));
        mockMvc.perform(get("/api/v1/food-references").param("q", "김치")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/food-references").with(authentication(AUTH)).param("q", "김치"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].kcalPerServing").value(196));
        for (String[] bad : List.of(new String[]{"", "10"}, new String[]{"가".repeat(51), "10"},
                new String[]{"김치", "0"}, new String[]{"김치", "21"})) {
            mockMvc.perform(get("/api/v1/food-references").with(authentication(AUTH))
                    .param("q", bad[0]).param("size", bad[1])).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/food-references").with(authentication(AUTH))).andExpect(status().isBadRequest());
        verify(nutritionService, never()).search(eq(""), anyInt());
    }

    @Test void menuNutritionValidatesKcalRange() throws Exception {
        given(nutritionService.updateMenuNutrition(eq(1L), eq(5L), any()))
                .willReturn(new NutritionDto.MenuNutrition(5L, 500, null, 2L));
        mockMvc.perform(put("/api/v1/menus/5/nutrition").with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kcalPerServing\":500}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.kcalPerServing").value(500))
                .andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(put("/api/v1/menus/5/nutrition").with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/menus/5/nutrition").with(authentication(AUTH)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kcalPerServing\":500,\"carbsG\":60.5,\"proteinG\":20,\"fatG\":0,\"fiberG\":3.1}"))
                .andExpect(status().isOk());
        for (String body : List.of("{\"kcalPerServing\":0}", "{\"kcalPerServing\":5001}", "{\"kcalPerServing\":\"많이\"}",
                "{\"kcalPerServing\":500,\"carbsG\":12.34}", "{\"kcalPerServing\":500,\"proteinG\":-1}",
                "{\"kcalPerServing\":500,\"fatG\":1000}", "{\"kcalPerServing\":500,\"fiberG\":1000.0}")) {
            mockMvc.perform(put("/api/v1/menus/5/nutrition").with(authentication(AUTH))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/api/v1/menus/5/nutrition").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void summaryPassesDaysThrough() throws Exception {
        given(nutritionService.summarize(1L, 14)).willReturn(new NutritionDto.NutritionSummary(
                14, List.of(), List.of(), 0, 0, 0, null, false));
        mockMvc.perform(get("/api/v1/history/nutrition").with(authentication(AUTH)).param("days", "14"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.periodDays").value(14))
                .andExpect(jsonPath("$.data.averageKcal").doesNotExist());
        verify(nutritionService, never()).search(anyString(), anyInt());
    }
}
