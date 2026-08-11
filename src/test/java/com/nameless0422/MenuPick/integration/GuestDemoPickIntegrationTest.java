package com.nameless0422.MenuPick.integration;

import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게스트 데모 픽이 <b>인증 없이</b> 실제로 열려 있는지 확인한다 (docs/Planning.md 4.3).
 *
 * <p>컨트롤러 슬라이스 테스트는 시큐리티 설정을 목으로 대체하므로 permitAll 규칙이 실제로
 * 걸렸는지 증명하지 못한다. 반대로 이 엔드포인트만 열리고 나머지는 닫혀 있어야 하므로,
 * 인증이 필요한 경로가 여전히 401인지도 같은 자리에서 함께 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class GuestDemoPickIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("로그인 없이 데모 픽을 호출하면 샘플 메뉴가 반환된다")
    void demoPick_withoutAuth_returnsSample() throws Exception {
        mockMvc.perform(get("/api/v1/pick/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").isNotEmpty())
                .andExpect(jsonPath("$.data.categories").isNotEmpty());
    }

    @Test
    @DisplayName("데모 응답에는 사용자에 귀속되는 값(historyId·id)이 없다")
    void demoPick_hasNoUserScopedFields() throws Exception {
        mockMvc.perform(get("/api/v1/pick/demo"))
                .andExpect(status().isOk())
                // 저장되지 않는 결과라 식별자가 붙으면 저장된 것처럼 오해를 준다
                .andExpect(jsonPath("$.data.historyId").doesNotExist())
                .andExpect(jsonPath("$.data.id").doesNotExist());
    }

    @Test
    @DisplayName("permitAll은 데모 경로에만 적용된다 — 실제 픽은 여전히 인증이 필요하다")
    void realPick_withoutAuth_isStillUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/pick"))
                .andExpect(status().isUnauthorized());
    }
}
