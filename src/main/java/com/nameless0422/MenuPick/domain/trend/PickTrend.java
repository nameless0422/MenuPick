package com.nameless0422.MenuPick.domain.trend;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 집계 결과 한 줄 — "최근 N일 동안 이 항목을 먹은 사람이 몇 명".
 *
 * <h2>읽기 전용 모델이다</h2>
 *
 * <p>이 표는 {@code histories}에서 파생된 결과이고, 하루 한 번 스케줄러가 통째로 갈아 끼운다
 * ({@link PickTrendAggregationService}). 요청 경로에서는 절대 쓰지 않는다 — 픽 한 번에 나가는
 * 쿼리 수는 {@code PickQueryBudgetTest}가 못을 박아 두었고, 결과 화면에 "이번 주 인기"를
 * 붙이겠다고 매 요청 {@code histories} 전체를 스캔하면 그 예산이 통째로 무너진다.
 *
 * <h2>{@code userCount}는 행 수가 아니라 사람 수다</h2>
 *
 * <p>행으로 세면 한 사람이 마음에 들 때까지 다시 돌린 것이 그대로 "인기"가 된다. 실제로 픽은
 * 다시 뽑기가 흔한 동작이라, 행 기준으로는 가장 자주 다시 뽑는 한 명이 순위표를 만든다.
 *
 * <h2>개인 식별자가 없다</h2>
 *
 * <p>이 표에는 {@code user_id}가 없고 앞으로도 넣지 않는다. 넣는 순간 "누가 무엇을 먹었는지"의
 * 사본이 되어 {@code histories}에 걸어 둔 보관·삭제 규칙을 우회하게 된다
 * ({@code docs/PrivacyReview.md}). 대신 사람 수만 남기고, 그 수가 문턱 미만인 항목은 애초에
 * 저장하지 않는다 — 2명이 먹은 것을 "인기"로 띄우면 아는 사이끼리는 누구인지 특정된다.
 */
@Entity
@Table(name = "pick_trends")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickTrend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrendDimension dimension;

    @Column(nullable = false, length = 100)
    private String label;

    /** 서로 다른 사용자 수. 근거는 클래스 주석. */
    @Column(nullable = false)
    private int userCount;

    /** 1부터. 같은 dimension 안에서의 순위이며 동점은 label 사전순으로 갈린다. */
    @Column(nullable = false)
    private int rankOrder;

    /** 집계한 기간(일). 화면이 "최근 7일"이라고 말할 근거를 데이터가 직접 들고 있게 한다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private PickTrendSnapshot snapshot;

    @Builder
    public PickTrend(TrendDimension dimension, String label, int userCount,
                     int rankOrder, PickTrendSnapshot snapshot) {
        this.dimension = dimension;
        this.label = label;
        this.userCount = userCount;
        this.rankOrder = rankOrder;
        this.snapshot = snapshot;
    }
}
