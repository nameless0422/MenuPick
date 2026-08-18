package com.nameless0422.MenuPick.common.exception;

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
}
