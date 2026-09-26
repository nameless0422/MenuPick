package com.nameless0422.MenuPick.domain.room;

import com.nameless0422.MenuPick.domain.room.dto.PickRoomResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같이 뽑기의 HTTP 경계.
 *
 * <p>여기서 보는 것은 <b>어디까지 열려 있는가</b>다. 만들기만 로그인이고 참여는 링크만 있으면
 * 된다는 결정이 이 파일에서 깨지면, 반대로 뒤집혀도(참여에 로그인이 필요해지거나, 만들기가
 * 열리거나) 화면은 한동안 멀쩡해 보인다.
 */
@WebMvcTest(PickRoomController.class)
class PickRoomControllerTest extends AbstractControllerTest {

    @MockitoBean private PickRoomService pickRoomService;

    private static PickRoomResponse room() {
        return new PickRoomResponse("abc123", LocalDateTime.of(2026, 9, 19, 18, 0),
                List.of(new PickRoomResponse.Menu(1L, "김치찌개", 2, true)), 3, null, false);
    }

    @Test
    @DisplayName("POST /pick/rooms - 로그인하면 방을 만들고 201을 준다")
    void create() throws Exception {
        given(pickRoomService.create(eq(1L), any())).willReturn(room());

        mockMvc.perform(post("/api/v1/pick/rooms").with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("abc123"))
                .andExpect(jsonPath("$.data.menus[0].vetoedBy").value(2));
    }

    /** 본문 없이 눌러도 "내 메뉴 전부로" 방이 만들어져야 한다 — 화면의 기본 동작이다. */
    @Test
    @DisplayName("POST /pick/rooms - 본문이 없어도 만들어진다")
    void create_withoutBody() throws Exception {
        given(pickRoomService.create(eq(1L), any())).willReturn(room());

        mockMvc.perform(post("/api/v1/pick/rooms").with(authentication(AUTH)))
                .andExpect(status().isCreated());
    }

    /** 자기 메뉴를 꺼내 공유하는 행위라 계정이 필요하다. */
    @Test
    @DisplayName("POST /pick/rooms - 미인증이면 401이고 만들지 않는다")
    void create_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/pick/rooms").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(pickRoomService, never()).create(any(), any());
    }

    /** 점심 자리에 있는 사람 전원이 가입해 있을 리 없다 — 참여는 링크만 있으면 된다. */
    @Test
    @DisplayName("GET /pick/rooms/{code} - 로그인 없이 볼 수 있다")
    void get_withoutLogin() throws Exception {
        given(pickRoomService.get("abc123", "p-1", null)).willReturn(room());

        mockMvc.perform(get("/api/v1/pick/rooms/abc123").param("participant", "p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participantCount").value(3))
                .andExpect(jsonPath("$.data.menus[0].vetoedByMe").value(true))
                // 아직 안 정해진 방은 결과를 지어내지 않는다.
                .andExpect(jsonPath("$.data.decision").doesNotExist());
    }

    @Test
    @DisplayName("PUT /pick/rooms/{code}/vetoes - 로그인 없이 제출할 수 있다")
    void replaceVetoes_withoutLogin() throws Exception {
        given(pickRoomService.replaceVetoes(eq("abc123"), any())).willReturn(room());

        mockMvc.perform(put("/api/v1/pick/rooms/abc123/vetoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participant\":\"p-1\",\"vetoedMenuIds\":[1]}"))
                .andExpect(status().isOk());
    }

    /** 참가자 식별자가 없으면 누구의 제외인지 알 수 없다 — 조용히 아무에게나 붙이지 않는다. */
    @Test
    @DisplayName("PUT /pick/rooms/{code}/vetoes - 참가자 식별자가 없으면 400")
    void replaceVetoes_requiresParticipant() throws Exception {
        mockMvc.perform(put("/api/v1/pick/rooms/abc123/vetoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vetoedMenuIds\":[1]}"))
                .andExpect(status().isBadRequest());
        verify(pickRoomService, never()).replaceVetoes(any(), any());
    }

    @Test
    @DisplayName("POST /pick/rooms/{code}/decide - 로그인 없이 뽑을 수 있다")
    void decide_withoutLogin() throws Exception {
        given(pickRoomService.decide("abc123")).willReturn(new PickRoomResponse(
                "abc123", LocalDateTime.of(2026, 9, 19, 18, 0), List.of(), 3,
                new PickRoomResponse.Decision("김치찌개", LocalDateTime.of(2026, 9, 19, 12, 30), null), false));

        mockMvc.perform(post("/api/v1/pick/rooms/abc123/decide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision.menuName").value("김치찌개"));
    }

    @Test
    @DisplayName("POST /pick/rooms/{code}/place - 로그인해야 식당을 고를 수 있다")
    void place_requiresLogin() throws Exception {
        String body = "{\"name\":\"식당\",\"latitude\":37.5665,\"longitude\":126.978," +
                "\"kakaoPlaceId\":\"place-1\"}";
        mockMvc.perform(post("/api/v1/pick/rooms/abc123/place")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        verify(pickRoomService, never()).choosePlace(any(), any(), any());

        given(pickRoomService.choosePlace(eq("abc123"), eq(1L), any())).willReturn(room());
        mockMvc.perform(post("/api/v1/pick/rooms/abc123/place")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
}
