package com.nameless0422.MenuPick.domain.tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.tag.dto.TagResponse;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TagController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class TagControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private TagService tagService;
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
    @DisplayName("GET /api/v1/tags?keyword=혼 - 태그 자동완성 검색 성공")
    void searchTags_success() throws Exception {
        given(tagService.searchTags(1L, "혼"))
                .willReturn(List.of(
                        new TagResponse.TagInfo(1L, "혼밥", LocalDateTime.now()),
                        new TagResponse.TagInfo(2L, "혼술", LocalDateTime.now())));

        mockMvc.perform(get("/api/v1/tags")
                        .param("keyword", "혼")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].name").value("혼밥"));
    }

    @Test
    @DisplayName("GET /api/v1/tags - 미인증 시 401")
    void searchTags_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/tags"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/tags - 태그 생성 성공 (201)")
    void createTag_success() throws Exception {
        given(tagService.createTag(eq(1L), any(TagRequest.Create.class)))
                .willReturn(new TagResponse.TagInfo(1L, "혼밥", LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/tags")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TagRequest.Create("혼밥"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("혼밥"));
    }

    @Test
    @DisplayName("POST /api/v1/tags - 빈 이름 시 400")
    void createTag_blankName() throws Exception {
        mockMvc.perform(post("/api/v1/tags")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TagRequest.Create(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/v1/tags/{tagId} - 삭제 성공")
    void deleteTag_success() throws Exception {
        mockMvc.perform(delete("/api/v1/tags/1")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(tagService).deleteTag(1L, 1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/tags/{tagId} - 미인증 시 401")
    void deleteTag_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/tags/1"))
                .andExpect(status().isUnauthorized());
    }
}
