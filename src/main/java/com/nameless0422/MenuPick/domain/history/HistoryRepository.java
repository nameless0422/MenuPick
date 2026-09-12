package com.nameless0422.MenuPick.domain.history;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HistoryRepository extends JpaRepository<History, Long> {

    @Modifying
    @Query("delete from HistoryFilterCondition fc where fc.history.id in " +
            "(select h.id from History h where h.user.id = :userId)")
    void deleteFilterConditionsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from History h where h.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    List<History> findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc(
            Long userId, LocalDateTime after, Long cursor, Pageable pageable);

    List<History> findByUserIdAndRecommendedAtAfterOrderByIdDesc(
            Long userId, LocalDateTime after, Pageable pageable);

    Optional<History> findByIdAndUserId(Long id, Long userId);

    /**
     * 개인화에 필요한 최근 추천 시각과 피드백 합계를 메뉴별 한 행으로 돌려준다.
     * 이벤트를 전부 애플리케이션으로 올리지 않고 DB에서 먼저 상쇄해 요청당 조회를 한 번으로
     * 제한한다. 합계는 이력이 많아도 넘치지 않도록 {@code Long}으로 받으며 서비스가 ±2로 자른다.
     */
    @Query("select h.menu.id as menuId, max(h.recommendedAt) as latestRecommendedAt, " +
            "sum(case when h.recommendationFeedback = :accepted then 1 " +
            "when h.recommendationFeedback = :rejected then -1 else 0 end) as feedbackScore " +
            "from History h where h.user.id = :userId and h.menu is not null " +
            "and h.recommendedAt >= :since group by h.menu.id")
    List<MenuRecommendationSignals> findMenuRecommendationSignalsSince(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since,
            @Param("accepted") RecommendationFeedback accepted,
            @Param("rejected") RecommendationFeedback rejected);

    interface MenuRecommendationSignals {
        Long getMenuId();
        LocalDateTime getLatestRecommendedAt();
        Long getFeedbackScore();
    }

    // ---------------------------------------------------------------
    // KPI 집계 (Specification.md 8장). 운영자만 보는 값이라 관리 포트의
    // /actuator/kpi 에서만 읽는다 — KpiEndpoint 참고.
    //
    // 셋 다 분모를 함께 돌려준다. 비율만 주면 "0%"와 "표본이 없음"을 구분할 수 없고,
    // 표본이 한 자릿수일 때 30%라는 숫자를 목표 달성으로 읽게 된다.
    // ---------------------------------------------------------------

    /** 기간 내 픽 수와 그중 방문 처리된 수. 픽 후 방문율의 분모·분자. */
    @Query("select count(h), sum(case when h.isVisited = true then 1 else 0 end) " +
            "from History h where h.recommendedAt >= :since")
    List<Object[]> countPicksAndVisitsSince(@Param("since") LocalDateTime since);

    /** 기간 내 한 번이라도 픽한 사용자 수. 재픽률의 분모. */
    @Query("select count(distinct h.user.id) from History h where h.recommendedAt >= :since")
    long countDistinctUsersSince(@Param("since") LocalDateTime since);

    /**
     * 기간 내 <b>같은 날에 두 번 이상</b> 픽한 사용자 수. 재픽률의 분자.
     *
     * <p>날짜는 {@code recommendedAt}을 그대로 자른다. 이 컬럼은 KST 기준으로 기록되고
     * ({@code TimeConfig.SERVICE_ZONE}, mysql 컨테이너도 {@code TZ=Asia/Seoul}) "같은 날"의
     * 기준도 사용자가 사는 시간대여야 하므로 변환하지 않는다.
     */
    @Query("select count(distinct u) from (" +
            "select h.user.id as u, function('date', h.recommendedAt) as d, count(h) as c " +
            "from History h where h.recommendedAt >= :since " +
            "group by h.user.id, function('date', h.recommendedAt) having count(h) >= 2)")
    long countUsersWithSameDayRepick(@Param("since") LocalDateTime since);

    // ---------------------------------------------------------------
    // 집단 통계 집계 (domain/trend). 하루 한 번 스케줄러에서만 호출한다 —
    // 요청 경로에서 부르면 픽 쿼리 예산(PickQueryBudgetTest)이 무너진다.
    //
    // 두 쿼리 모두 선택·방문 신호가 있는 이력만 센다: 방문 처리했거나(isVisited)
    // 추천을 수락했거나(ACCEPTED). 그냥 뽑기만 한 것은 취향의 증거가 아니다 —
    // 마음에 안 들어 다시 돌린 결과도 똑같이 한 행으로 남기 때문이다.
    //
    // 세는 단위는 행이 아니라 count(distinct user)다. 근거는 PickTrend 주석.
    // ---------------------------------------------------------------

    /**
     * 기간 내 카테고리별 선택·방문 사용자 수. 탈퇴 중인 사용자와 미래 시각은 제외한다.
     *
     * <p>삭제된 메뉴({@code deletedAt})도 제외하지 않는다. 이력은 그때 실제로 일어난 일의
     * 기록이고, 나중에 메뉴를 지웠다고 해서 그날 먹었다는 사실이 없던 일이 되지는 않는다.
     * (탈퇴 사용자의 이력은 다르다 — 유예기간이 지나면 {@code histories}에서 통째로 사라지므로
     * 여기서 따로 거를 것이 없다.)
     */
    @Query(value = "select mc.category as label, count(distinct h.user_id) as userCount " +
            "from histories h join users u on u.id=h.user_id " +
            "join menu_categories mc on mc.menu_id=h.menu_id " +
            "where h.recommended_at >= :since and h.recommended_at < :until " +
            "and u.deleted_at is null and binary mc.category in (:labels) " +
            "and (h.is_visited = true or h.recommendation_feedback = :accepted) " +
            "group by mc.category", nativeQuery = true)
    List<TrendCount> countUsersByCategorySince(@Param("since") LocalDateTime since,
                                               @Param("until") LocalDateTime until,
                                               @Param("labels") List<String> labels,
                                               @Param("accepted") String accepted);

    /**
     * 기간 내 canonical 메뉴 이름별 선택·방문 사용자 수.
     *
     * <p>{@code menu.id}가 아니라 이름으로 묶는다. id는 사용자마다 다른 행이라 그대로 묶으면
     * 모든 항목이 1명이 된다. 이름으로 묶는 것은 기본 메뉴 22개가 전 계정에 같은 문자열로
     * 깔리기 때문에 성립한다(V10). 직접 만든 이름은 인원수와 무관하게 IN 화이트리스트에서 제외한다.
     */
    @Query(value = "select m.name as label, count(distinct h.user_id) as userCount " +
            "from histories h join users u on u.id=h.user_id join menus m on m.id=h.menu_id " +
            "where h.recommended_at >= :since and h.recommended_at < :until " +
            "and u.deleted_at is null and binary m.name in (:labels) " +
            "and (h.is_visited = true or h.recommendation_feedback = :accepted) " +
            "group by m.name", nativeQuery = true)
    List<TrendCount> countUsersByMenuNameSince(@Param("since") LocalDateTime since,
                                               @Param("until") LocalDateTime until,
                                               @Param("labels") List<String> labels,
                                               @Param("accepted") String accepted);

    /** 집계 한 줄. 라벨과 사람 수만 나온다 — 이 밖의 것은 통계에 필요하지 않다. */
    interface TrendCount {
        String getLabel();
        Long getUserCount();
    }

    /** 사용자별 첫 픽 시각. 7일 리텐션 코호트를 만드는 근거. */
    @Query("select h.user.id, min(h.recommendedAt) from History h group by h.user.id")
    List<Object[]> findFirstPickPerUser();

    /**
     * 주어진 사용자들의 픽 시각 전부. (userId, recommendedAt) 쌍으로 돌아온다.
     *
     * <p>리텐션 판정을 SQL에 내리지 않는 이유: "첫 픽 이후 7일 이내"를 SQL로 쓰려면 사용자마다
     * 다른 기준 시각에 INTERVAL을 더해야 하는데, JPQL에는 그 표현이 없어 네이티브 MySQL 문법에
     * 묶인다. 무엇보다 이 지표의 어려운 부분은 조인이 아니라 <b>코호트 경계</b>다(아래 KpiService
     * 참고) — 그 판단은 눈에 보이는 자바 코드에 두는 편이 검증하기 쉽다.
     *
     * <p>대상은 코호트로 이미 좁혀져 들어온다. 전 사용자의 전 픽을 올리는 쿼리가 아니다.
     */
    @Query("select h.user.id, h.recommendedAt from History h where h.user.id in :userIds")
    List<Object[]> findPickTimesForUsers(@Param("userIds") List<Long> userIds);
}
