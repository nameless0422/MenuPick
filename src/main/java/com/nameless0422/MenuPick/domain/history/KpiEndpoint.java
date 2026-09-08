package com.nameless0422.MenuPick.domain.history;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 추천 품질 KPI를 운영자에게만 보여주는 액추에이터 엔드포인트.
 *
 * <h2>왜 서비스 API가 아니라 관리 포트인가</h2>
 *
 * <p>이 값은 서비스 전체 집계라 특정 사용자의 것이 아니다. {@code /api/v1}에 두려면 "관리자만"
 * 이라는 경계가 필요한데 이 앱에는 <b>역할(Role) 개념이 아직 없다</b>([D-020] 트레이드오프
 * 항목에 그대로 적혀 있다). 그 하나를 위해 RBAC을 새로 들이는 것보다, 이미 같은 목적으로
 * 만들어 둔 경계를 쓰는 편이 작고 정확하다.
 *
 * <p>관리 포트(9090)는 [D-027]에 따라 컨테이너 루프백에만 바인딩되고 compose가 publish하지
 * 않는다. 즉 <b>운영자가 {@code docker exec}으로만 닿을 수 있다.</b> 인증을 요구하지 않는
 * 대신 "애초에 닿을 수 없게" 막는 구조이고, 이 KPI는 정확히 그 경계 안쪽에 있어야 할 데이터다.
 *
 * <pre>
 *   docker exec menupick-app curl -s localhost:9090/actuator/kpi
 *   docker exec menupick-app curl -s "localhost:9090/actuator/kpi?lookbackDays=7"
 * </pre>
 *
 * <p>노출은 프로파일 설정의 {@code management.endpoints.web.exposure.include}가 정한다.
 * 거기에 {@code kpi}를 넣지 않으면 이 클래스가 있어도 404다 — 노출은 코드가 아니라 설정의
 * 결정으로 남긴다.
 */
@Component
@Endpoint(id = "kpi")
@RequiredArgsConstructor
public class KpiEndpoint {

    /**
     * 기본 집계 기간. 30일인 이유는 개인화 보정이 보는 창과 같기 때문이다
     * ({@code PickService}의 피드백 보정도 최근 30일을 본다) — 두 창이 어긋나면
     * "보정이 효과가 있었는가"를 물을 때 서로 다른 기간을 비교하게 된다.
     */
    private static final int DEFAULT_LOOKBACK_DAYS = 30;

    /** 기간을 무한정 늘려 전 구간 집계를 매번 돌리지 않도록 상한을 둔다. */
    private static final int MAX_LOOKBACK_DAYS = 365;

    private final KpiService kpiService;

    /**
     * {@code @Nullable}이 없으면 액추에이터가 이 파라미터를 <b>필수</b>로 보고,
     * 값을 빼고 부르면 400 "Missing parameters: lookbackDays"를 낸다.
     * 기본값으로 부르는 쪽이 압도적으로 흔하므로 선택 파라미터로 둔다.
     */
    @ReadOperation
    public Map<String, Object> kpi(@Nullable Integer lookbackDays) {
        int days = lookbackDays == null ? DEFAULT_LOOKBACK_DAYS : lookbackDays;
        if (days < 1 || days > MAX_LOOKBACK_DAYS) {
            days = DEFAULT_LOOKBACK_DAYS;
        }
        return kpiService.collect(days);
    }
}
