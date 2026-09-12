package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 빠른 픽 API의 HTTP 계약({@code docs/PickPresetDesign.md} 5절).
 *
 * <p>여기서 보는 것은 <b>경계</b>다 — 인증이 없으면 닿지 않는가, 상태 코드가 약속대로인가,
 * 응답에 넣지 않기로 한 것이 새지 않는가.
 */
@WebMvcTest(PickPresetController.class)
class PickPresetControllerTest extends AbstractControllerTest {

    @MockitoBean private PickPresetService pickPresetService;

    private PickPresetResponse.Detail detail() {
        return new PickPresetResponse.Detail(1L, "회사 점심", Set.of("한식"), Set.of(7L),
                Set.of(9L), 500, false, 0L, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    @DisplayName("GET /pick/presets - 목록과 상한을 함께 준다")
    void list() throws Exception {
        given(pickPresetService.list(1L))
                .willReturn(PickPresetResponse.ListResult.of(List.of(detail())));

        mockMvc.perform(get("/api/v1/pick/presets").with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.presets[0].name").value("회사 점심"))
                .andExpect(jsonPath("$.data.limit").value(10));
    }

    /**
     * 프리셋은 개인 선호 정보다 — 알레르기·기피·종교적 식습관을 추론할 여지가 있다.
     * 소유자 id가 응답에 실리면 그 연결고리가 하나 더 생긴다.
     */
    @Test
    @DisplayName("응답에 userId·좌표를 담지 않는다")
    void responseOmitsOwnerAndCoordinates() throws Exception {
        given(pickPresetService.list(1L))
                .willReturn(PickPresetResponse.ListResult.of(List.of(detail())));

        mockMvc.perform(get("/api/v1/pick/presets").with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.presets[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.presets[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.data.presets[0].longitude").doesNotExist());
    }

    @Test
    @DisplayName("POST /pick/presets - 생성은 201")
    void create() throws Exception {
        given(pickPresetService.create(eq(1L), any())).willReturn(detail());

        mockMvc.perform(post("/api/v1/pick/presets")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PickPresetRequest.Create(
                                "회사 점심", Set.of("한식"), Set.of(7L), Set.of(9L), 500))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("회사 점심"));
    }

    @Test
    @DisplayName("POST /pick/presets - 이름이 비면 400")
    void create_blankName() throws Exception {
        mockMvc.perform(post("/api/v1/pick/presets")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PickPresetRequest.Create(
                                "   ", Set.of(), Set.of(), Set.of(), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /pick/presets/{id} - 버전을 쿼리로 받는다")
    void delete_passesVersion() throws Exception {
        mockMvc.perform(delete("/api/v1/pick/presets/1")
                        .param("version", "3")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk());

        verify(pickPresetService).delete(1L, 1L, 3L);
    }

    @Test
    @DisplayName("POST /pick/presets/{id}/pick - 실행 결과에 적용된 조건이 함께 온다")
    void execute() throws Exception {
        given(pickPresetService.execute(eq(1L), eq(1L), any()))
                .willReturn(new PickPresetResponse.ExecutionResult(
                        null,
                        new PickPresetResponse.AppliedFilters(
                                Set.of("한식"), Set.of(7L), Set.of(9L, 11L), 500)));

        mockMvc.perform(post("/api/v1/pick/presets/1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedFilters.effectiveExcludeTagIds").isArray());
    }

    @Test
    @DisplayName("실행에 버전이 없으면 400")
    void execute_requiresVersion() throws Exception {
        mockMvc.perform(post("/api/v1/pick/presets/1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("검토가 필요한 프리셋 실행은 409이고 안내 문구를 담는다")
    void execute_needsReviewIsConflict() throws Exception {
        willThrow(new BusinessException(ErrorCode.PICK_PRESET_NEEDS_REVIEW))
                .given(pickPresetService).execute(eq(1L), eq(1L), any());

        mockMvc.perform(post("/api/v1/pick/presets/1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PICK_PRESET_NEEDS_REVIEW"));
    }

    @Test
    @DisplayName("모든 경로는 인증을 요구한다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/pick/presets")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/pick/presets")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/pick/presets/1/pick")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/pick/presets/1").param("version", "0"))
                .andExpect(status().isUnauthorized());
    }
}
