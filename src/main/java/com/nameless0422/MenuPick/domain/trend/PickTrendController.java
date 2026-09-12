package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.trend.dto.PickTrendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 최근 사용자들이 선택하거나 방문 처리한 canonical 메뉴·카테고리 통계.
 *
 * <h2>인증이 필요하다</h2>
 *
 * <p>{@code SecurityConfig}의 {@code anyRequest().authenticated()}에 그대로 걸린다 —
 * {@code permitAll} 목록에 넣지 않은 것이 결정이다. 개인정보가 담기지 않은 집계값이라 공개해도
 * 식별자를 담지 않아도 익명성이 보장되는 것은 아니다. 공개하면 <b>가입하지 않은 쪽에서 반복 수집</b>할 수 있다. 이 값은
 * 서비스가 얼마나 쓰이는지를 드러내는 지표이기도 해서(문턱을 넘은 항목 수 = 최소 사용자 규모의
 * 하한) 굳이 바깥에 내놓을 이유가 없다. 화면에 붙는 자리도 로그인해야 들어가는 픽 화면이다.
 *
 * <p>응답은 <b>모든 사용자에게 동일하다.</b> 개인화하지 않는다 — 개인 취향 보정은 픽 자체가
 * 이미 하고 있고({@code PickService}의 최근 피드백 보정), 여기까지 개인화하면 "사람들이 많이
 * 선택·방문한 항목이라는 집단 통계 문구가 거짓이 된다.
 */
@RestController
@RequestMapping("/api/v1/trends")
@RequiredArgsConstructor
public class PickTrendController {

    private final PickTrendService pickTrendService;

    @GetMapping
    public ResponseEntity<ApiResponse<PickTrendResponse>> trends() {
        return ResponseEntity.ok(ApiResponse.ok(pickTrendService.currentTrends()));
    }
}
