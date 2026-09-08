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

    @Query("select distinct h.menu.id from History h " +
            "where h.user.id = :userId and h.menu is not null and h.recommendedAt >= :since")
    List<Long> findDistinctMenuIdsRecommendedSince(
            @Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Query("select h.menu.id from History h " +
            "where h.user.id = :userId and h.menu is not null " +
            "and h.recommendationFeedback = :feedback and h.recommendedAt >= :since")
    List<Long> findMenuIdsByFeedbackSince(
            @Param("userId") Long userId,
            @Param("feedback") RecommendationFeedback feedback,
            @Param("since") LocalDateTime since);

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
