package com.nameless0422.MenuPick.domain.room;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.room.dto.PickRoomRequest;
import com.nameless0422.MenuPick.domain.room.dto.PickRoomResponse;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 여럿이 같이 뽑기.
 *
 * <h2>방 참여는 링크, 만들기와 식당 선택은 로그인</h2>
 *
 * <p>방을 만드는 것은 자기 메뉴를 꺼내 공유하는 행위라 계정이 필요하다. 반대로 참여는 링크를
 * 받은 사람이면 누구나 할 수 있어야 한다 — 점심 자리에 있는 사람 전원이 가입해 있을 리 없고,
 * 가입을 요구하는 순간 방을 만들 이유가 사라진다. 그래서 조회·제외·뽑기는
 * {@code SecurityConfig}에서 permitAll이고, 방 코드가 곧 입장 자격이다. 식당 선택은 방장의
 * 개인 데이터에 저장하므로 로그인과 소유 확인이 필요하다.
 *
 * <p>인증이 없는 만큼 남용 한도는 {@code RateLimitFilter}가 IP 기준으로 건다. 그 밖의 한도
 * (방 수·메뉴 수·참가자 수·6시간 수명·결과 확정 후 불변)는 {@link PickRoomService}에 있다.
 */
@RestController
@RequestMapping("/api/v1/pick/rooms")
@RequiredArgsConstructor
public class PickRoomController {

    private final PickRoomService pickRoomService;

    @PostMapping
    public ResponseEntity<ApiResponse<PickRoomResponse>> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody(required = false) @Valid PickRoomRequest.Create request) {
        PickRoomRequest.Create body = request != null ? request : new PickRoomRequest.Create(null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(pickRoomService.create(userId, body)));
    }

    /**
     * 방 상태. {@code participant}를 주면 그 참가자가 뺀 것이 함께 표시된다 — 없으면 남의 선택은
     * 숫자로만 보인다.
     */
    @GetMapping("/{code}")
    public ResponseEntity<ApiResponse<PickRoomResponse>> get(
            @PathVariable String code,
            @RequestParam(required = false) String participant,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(pickRoomService.get(code, participant, userId)));
    }

    /** 제외 제출. 부분 갱신이 아니라 <b>전체 교체</b>다 — 근거는 {@code PickRoomRequest.Vetoes}. */
    @PutMapping("/{code}/vetoes")
    public ResponseEntity<ApiResponse<PickRoomResponse>> replaceVetoes(
            @PathVariable String code,
            @RequestBody @Valid PickRoomRequest.Vetoes request) {
        return ResponseEntity.ok(ApiResponse.ok(pickRoomService.replaceVetoes(code, request)));
    }

    /**
     * 뽑기. 이미 정해진 방이면 같은 결과를 그대로 돌려준다(멱등) — 그래서 두 사람이 동시에
     * 눌러도 결과가 갈리지 않는다.
     */
    @PostMapping("/{code}/decide")
    public ResponseEntity<ApiResponse<PickRoomResponse>> decide(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(pickRoomService.decide(code)));
    }

    /** 방장만 식당을 정한다. 방 링크를 받은 사람은 결과를 읽을 수 있다. */
    @PostMapping("/{code}/place")
    public ResponseEntity<ApiResponse<PickRoomResponse>> choosePlace(
            @PathVariable String code,
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid RestaurantRequest.Create request) {
        return ResponseEntity.ok(ApiResponse.ok(pickRoomService.choosePlace(code, userId, request)));
    }
}
