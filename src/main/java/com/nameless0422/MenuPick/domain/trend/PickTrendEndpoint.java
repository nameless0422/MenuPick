package com.nameless0422.MenuPick.domain.trend;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 집단 통계를 <b>지금</b> 다시 계산하게 하는 운영자용 손잡이.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>집계는 하루 한 번 04:10에만 돈다({@link PickTrendScheduler}). 이게 없으면 배포한 기능이
 * 실제로 도는지 확인하려면 <b>다음 새벽까지 기다려야 한다.</b> 배포 직후의 표는 비어 있고,
 * 화면은 "표본이 모이면 보여드려요"를 띄우는데 — 그것이 정상 동작인지 스케줄러가 아예 안
 * 도는 것인지 구분할 방법이 없다. 그 상태로 하루를 보내는 것이 이 엔드포인트를 두는 이유다.
 * 문턱({@code trends.min-users})을 조정한 뒤 결과를 바로 확인하는 데도 쓴다.
 *
 * <h2>왜 관리 포트인가</h2>
 *
 * <p>{@code KpiEndpoint}와 같은 경계다. 관리 포트(9090)는 컨테이너 루프백에만 바인딩되고
 * compose가 publish하지 않으므로([D-027]) 운영자가 {@code docker exec}으로만 닿는다. 이건
 * 전체 재계산을 일으키는 <b>쓰기</b> 동작이라 더더욱 서비스 API에 둘 수 없다 — 역할(Role)
 * 개념이 없는 상태에서 {@code /api/v1}에 두면 아무 로그인 사용자나 부를 수 있다.
 *
 * <pre>
 *   docker exec menupick-app curl -s -XPOST localhost:9090/actuator/trends
 * </pre>
 *
 * <p>{@code management.endpoints.web.exposure.include}에 {@code trends}가 없으면 이 클래스가
 * 있어도 404다 — 노출은 코드가 아니라 설정의 결정으로 남긴다.
 */
@Slf4j
@Component
@Endpoint(id = "trends")
@RequiredArgsConstructor
public class PickTrendEndpoint {

    private final PickTrendAggregationService aggregationService;

    /**
     * {@code @WriteOperation}이라 POST로만 닿는다 — GET으로 재계산이 일어나면 브라우저
     * 프리페치나 헬스체크 같은 것이 무심코 부를 수 있다.
     */
    @WriteOperation
    public Map<String, Object> recompute() {
        log.info("집단 통계 수동 재집계 요청 — 관리 포트");
        PickTrendAggregationService.Result result = aggregationService.recompute();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryCount", result.categoryCount());
        body.put("menuCount", result.menuCount());
        body.put("windowDays", result.windowDays());
        body.put("minUsers", result.minUsers());
        body.put("enabled", result.enabled());
        if (result.computedAt() != null) body.put("computedAt", result.computedAt().toString());
        if (!result.enabled()) {
            body.put("note", "집단 통계가 비활성화되어 집계하지 않았다.");
            return body;
        }
        if (result.categoryCount() == 0 && result.menuCount() == 0) {
            // 0건은 실패가 아니다. 응답만 보고 "안 돌았다"로 읽지 않도록 여기서 못을 박는다.
            body.put("note", "최근 %d일 동안 %d명 이상이 선택·방문한 항목이 없다. 집계는 정상적으로 돌았다."
                    .formatted(result.windowDays(), result.minUsers()));
        }
        return body;
    }
}
