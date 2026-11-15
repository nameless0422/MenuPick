package com.nameless0422.MenuPick.common.exception;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.pick.DemoPickService;
import com.nameless0422.MenuPick.domain.pick.PickController;
import com.nameless0422.MenuPick.domain.pick.PickService;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    /**
     * 낙관적 락 충돌은 고장이 아니라 정상적인 거절이다 (issue #87, V9).
     *
     * <p>전용 핸들러가 없으면 이 예외도 맨 아래 catch-all에 잡혀 500 + ERROR 스택트레이스가
     * 된다 — 사용자는 "서버 내부 오류"를 보고 다시 눌러 볼 생각을 못 하고(사실은 새로고침 후
     * 다시 저장하면 되는 상황이다), 로그는 스택트레이스로 밀린다. 405/415를 여기서 잡아 둔
     * 것과 같은 이유다.
     */
    @Test
    @DisplayName("낙관적 락 충돌은 500이 아니라 409 + CONCURRENT_MODIFICATION")
    void optimisticLockFailure_returns409() throws Exception {
        given(pickService.pick(anyLong(), any()))
                .willThrow(new ObjectOptimisticLockingFailureException(Menu.class, 1L));

        mockMvc.perform(post("/api/v1/pick")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
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


    /**
     * 깨진 본문의 파싱 오류 메시지에는 <b>본문의 조각</b>이 들어간다 — 파서가 "여기서 막혔다"를
     * 설명하려고 그 자리의 토큰을 인용하기 때문이다. 로그인·가입·비밀번호 변경 요청이 깨져서
     * 오면 그 조각이 비밀번호일 수 있다(따옴표를 빠뜨린 값이 그대로 인용된다). 로그는 평문으로
     * journal에 14일 넘게 남고 알림 채널로도 나갈 수 있으므로, 본문에서 온 글자는 남기지 않는다.
     */
    @Test
    @DisplayName("깨진 JSON의 내용은 로그에 남기지 않는다 — 본문 조각이 비밀번호일 수 있다")
    void malformedBody_doesNotLogBodyFragments() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(post("/api/v1/pick")
                            .with(authentication(AUTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\": hunter2SecretValue}"))
                    .andExpect(status().isBadRequest());

            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .as("무슨 종류의 오류였는지는 남아야 한다")
                    .anySatisfy(event -> org.assertj.core.api.Assertions
                            .assertThat(event.getFormattedMessage())
                            .contains("HttpMessageNotReadableException"));
            org.assertj.core.api.Assertions.assertThat(appender.list)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("hunter2SecretValue"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 타입이 안 맞는 값도 사용자가 보낸 그대로다. 값 전체를 찍으면 URL에 실려 온 긴 문자열이
     * 통째로 로그에 들어간다 — 무엇이 왔는지 짐작할 만큼만 남긴다.
     */
    @Test
    @DisplayName("타입이 안 맞는 파라미터 값은 잘라서 남긴다")
    void typeMismatch_truncatesLoggedValue() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String longValue = "x".repeat(3_000);
            GlobalExceptionHandler handler = new GlobalExceptionHandler();
            handler.handleTypeMismatch(new org.springframework.web.method.annotation
                    .MethodArgumentTypeMismatchException(longValue, Long.class, "menuId", null,
                    new NumberFormatException("bad")));

            org.assertj.core.api.Assertions.assertThat(appender.list).hasSize(1);
            String logged = appender.list.get(0).getFormattedMessage();
            org.assertj.core.api.Assertions.assertThat(logged).contains("param=menuId");
            org.assertj.core.api.Assertions.assertThat(logged.length()).isLessThan(200);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
