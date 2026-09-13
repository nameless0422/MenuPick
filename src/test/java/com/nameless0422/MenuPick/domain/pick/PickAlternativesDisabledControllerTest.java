package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PickAlternativesController.class)
@TestPropertySource(properties = "pick-alternatives.enabled=false")
class PickAlternativesDisabledControllerTest extends AbstractControllerTest {
    @Test
    void disabledEndpoint_isNotRegistered() throws Exception {
        mockMvc.perform(post("/api/v1/pick/alternatives").with(authentication(AUTH))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
}
