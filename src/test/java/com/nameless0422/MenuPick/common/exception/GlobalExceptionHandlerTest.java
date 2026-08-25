package com.nameless0422.MenuPick.common.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import com.nameless0422.MenuPick.domain.pick.DemoPickService;
import com.nameless0422.MenuPick.domain.pick.PickController;
import com.nameless0422.MenuPick.domain.pick.PickService;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC가 직접 던지는 예외(405/415)가 catch-all로 새지 않는지 확인한다.
 *
 * <p>{@code ExceptionHandlerExceptionResolver}가 {@code DefaultHandlerExceptionResolver}보다
 * 먼저 돌기 때문에, 전용 핸들러가 없으면 이 요청들이 {@code @ExceptionHandler(Exception.class)}에
 * 잡혀 500 + ERROR 스택트레이스가 된다. 스캐너 봇의 PUT/DELETE/TRACE 한 번에 ERROR 로그가
 * 수천 줄 쌓이고 사고 직전 로그가 롤아웃되는 경로라 상태 코드를 회귀로 잡아 둔다.
 *
 * <p>컨트롤러는 아무거나 하나 있으면 되므로 매핑이 단순한 {@link PickController}를 빌려 쓴다.
 */
@WebMvcTest(PickController.class)
class GlobalExceptionHandlerTest extends AbstractControllerTest {

    @MockitoBean private PickService pickService;
    @MockitoBean private DemoPickService demoPickService;

    @Test
    @DisplayName("매핑되지 않은 HTTP 메서드는 500이 아니라 405 + Allow 헤더")
    void unsupportedMethod_returns405() throws Exception {
        mockMvc.perform(delete("/api/v1/pick")
                        .with(authentication(AUTH)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.success").value(false));

        // 핸들러까지 내려가지 않았음을 확인 — 405는 디스패치 단계에서 끝난다
        verifyNoInteractions(pickService);
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type은 500이 아니라 415")
    void unsupportedMediaType_returns415() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("categories=한식"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(pickService);
    }

    // --- 응답 스키마 일관성 (#87) ---
    //
    // 예전에는 BusinessException 핸들러만 errorCode를 채우고 나머지(409·400·405·415·404·500)는
    // 레거시 오버로드를 써 errorCode가 null이 됐다. @JsonInclude(NON_NULL) 때문에 필드 자체가
    // 사라지므로, 같은 상태 코드에 두 가지 스키마가 존재한다. 프론트가 errorCode로 분기하면
    // 어느 쪽이 오느냐에 따라 조용히 깨진다.

    @Test
    @DisplayName("405 응답에도 errorCode가 담긴다")
    void unsupportedMethod_carriesErrorCode() throws Exception {
        mockMvc.perform(delete("/api/v1/pick")
                        .with(authentication(AUTH)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("415 응답에도 errorCode가 담긴다")
    void unsupportedMediaType_carriesErrorCode() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("categories=한식"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("깨진 JSON 본문 400 응답에도 errorCode가 담긴다")
    void malformedBody_carriesErrorCode() throws Exception {
        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));
    }

    @Test
    @DisplayName("예상치 못한 500 응답에도 errorCode가 담긴다")
    void unexpectedError_carriesErrorCode() throws Exception {
        given(pickService.pick(anyLong(), any())).willThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"));
    }

}
