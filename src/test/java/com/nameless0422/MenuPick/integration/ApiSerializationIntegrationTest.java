package com.nameless0422.MenuPick.integration;

import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 응답 직렬화까지 실제로 태우는 통합 테스트.
 *
 * <p>서비스 단위 테스트는 DTO를 자바 객체로만 확인하므로, 트랜잭션이 닫힌 뒤
 * Jackson이 LAZY 컬렉션을 건드려 터지는 회귀(open-in-view=false + LazyInitializationException)를
 * 잡지 못한다. 여기서는 MockMvc로 컨트롤러 → 서비스 → JSON 직렬화 전 구간을 통과시켜
 * 응답 본문에 컬렉션이 정상적으로 실려 나가는지 검증한다.
 *
 * <p>Testcontainers MySQL + Flyway 스키마 위에서 돌며({@link AbstractIntegrationTest}),
 * 인증은 컨트롤러 슬라이스 테스트와 동일하게 인증 객체(principal = userId) 주입으로 대신한다.
 * 각 테스트는 자기 사용자만 조회하므로 같은 컨테이너를 공유해도 서로 간섭하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class ApiSerializationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private TagRepository tagRepository;

    private RequestPostProcessor asUser;
    private Long tagId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("serialization-" + UUID.randomUUID() + "@test.com")
                .nickname("직렬화유저")
                .build());
        asUser = authentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
        tagId = tagRepository.save(Tag.builder().user(user).name("혼밥").build()).getId();
    }

    @Test
    @DisplayName("메뉴 생성 → 목록/상세 조회 응답에 카테고리·태그가 그대로 실린다 (LazyInitializationException 회귀 방지)")
    void createMenu_thenListAndDetail_serializeLazyCollections() throws Exception {
        String body = """
                {"name":"김치찌개","memo":"국물이 끝내줌","weight":3,
                 "categories":["한식","찌개"],"tagIds":[%d]}
                """.formatted(tagId);

        String created = mockMvc.perform(post("/api/v1/menus")
                        .with(asUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("김치찌개"))
                .andExpect(jsonPath("$.data.categories.length()").value(2))
                .andExpect(jsonPath("$.data.tags[0].name").value("혼밥"))
                .andReturn().getResponse().getContentAsString();

        Number menuId = JsonPath.read(created, "$.data.id");

        mockMvc.perform(get("/api/v1/menus").with(asUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menus.length()").value(1))
                .andExpect(jsonPath("$.data.menus[0].name").value("김치찌개"))
                .andExpect(jsonPath("$.data.menus[0].categories.length()").value(2))
                .andExpect(jsonPath("$.data.menus[0].tags[0].name").value("혼밥"))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        mockMvc.perform(get("/api/v1/menus/" + menuId.longValue()).with(asUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memo").value("국물이 끝내줌"))
                .andExpect(jsonPath("$.data.categories.length()").value(2))
                .andExpect(jsonPath("$.data.tags[0].name").value("혼밥"))
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    /**
     * 필터 없는 픽은 카테고리 컬렉션을 한 번도 건드리지 않으므로, {@code toDetail}이 LAZY 컬렉션을
     * 그대로 DTO에 담으면 세션이 닫힌 뒤 직렬화 시점에 {@code LazyInitializationException}(500)이
     * 났다. 컨트롤러 슬라이스 테스트는 서비스를 목으로 대체해 이 회귀를 구조적으로 잡을 수 없어
     * 실제 직렬화까지 타는 이 통합 테스트로 고정한다.
     */
    @Test
    @DisplayName("필터 없는 픽도 응답의 카테고리·태그가 직렬화된다")
    void pick_withoutFilter_serializesLazyCollections() throws Exception {
        mockMvc.perform(post("/api/v1/menus")
                        .with(asUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"비빔밥\",\"weight\":1,\"categories\":[\"한식\"],\"tagIds\":[%d]}"
                                .formatted(tagId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/pick").with(asUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menu.name").value("비빔밥"))
                .andExpect(jsonPath("$.data.menu.categories.length()").value(1))
                .andExpect(jsonPath("$.data.menu.tags[0].name").value("혼밥"));
    }

    @Test
    @DisplayName("픽 실행 → 히스토리 조회에 방금 픽한 1건이 필터 조건과 함께 남는다")
    void pick_thenHistory_returnsSingleEntry() throws Exception {
        mockMvc.perform(post("/api/v1/menus")
                        .with(asUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"돈까스\",\"weight\":1,\"categories\":[\"일식\"]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/pick")
                        .with(asUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categories\":[\"일식\"],\"maxDistance\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.historyId").isNumber())
                .andExpect(jsonPath("$.data.menu.name").value("돈까스"))
                .andExpect(jsonPath("$.data.menu.categories.length()").value(1))
                .andExpect(jsonPath("$.data.restaurants").isEmpty());

        mockMvc.perform(get("/api/v1/history").with(asUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.histories.length()").value(1))
                .andExpect(jsonPath("$.data.histories[0].menuName").value("돈까스"))
                .andExpect(jsonPath("$.data.histories[0].isVisited").value(false))
                .andExpect(jsonPath("$.data.histories[0].recommendedAt").exists())
                .andExpect(jsonPath("$.data.histories[0].filterConditions[?(@.filterType == 'CATEGORY')].filterValue")
                        .value("일식"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }
}
