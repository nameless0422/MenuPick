package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.pick.dto.PickAlternativesResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PickAlternativesController.class)
@TestPropertySource(properties = "pick-alternatives.enabled=true")
class PickAlternativesControllerTest extends AbstractControllerTest {
    @MockitoBean PickAlternativesService service;

    @Test
    void authenticatedRequest_returnsStableShapeWithoutSensitiveInput() throws Exception {
        given(service.find(eq(1L), any(PickRequest.class))).willReturn(new PickAlternativesResponse(List.of(
                new PickAlternativesResponse.Alternative(PickAlternativesResponse.Type.EXPAND_DISTANCE,
                        3, PickAlternativesResponse.Changes.distance(1000)))));

        mockMvc.perform(post("/api/v1/pick/alternatives").with(authentication(AUTH))
                        .contentType("application/json")
                        .content("{\"categories\":[\"secret\"],\"tagIds\":[91],\"latitude\":37," +
                                "\"longitude\":127,\"maxDistance\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alternatives[0].type").value("EXPAND_DISTANCE"))
                .andExpect(jsonPath("$.data.alternatives[0].candidateCount").value(3))
                .andExpect(jsonPath("$.data.alternatives[0].changes.maxDistance").value(1000))
                .andExpect(jsonPath("$..latitude").doesNotExist())
                .andExpect(jsonPath("$..tagIds").doesNotExist());
    }

    @Test
    void unauthenticatedRequest_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/pick/alternatives").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCategory_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/pick/alternatives").with(authentication(AUTH))
                        .contentType("application/json").content("{\"categories\":[\"   \"]}"))
                .andExpect(status().isBadRequest());
    }
}
