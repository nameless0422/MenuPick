package com.nameless0422.MenuPick.domain.trend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PickTrendRepository extends JpaRepository<PickTrend, Long> {

    /** 화면이 쓰는 유일한 조회. 항상 "한 축을 순위순으로"다. */
    List<PickTrend> findByDimensionOrderByRankOrderAsc(TrendDimension dimension);

    /**
     * 집계 전에 전부 비운다.
     *
     * <p>{@code deleteAll()}이 아니라 벌크 DELETE인 이유: 전자는 전 행을 엔티티로 올린 뒤
     * 한 건씩 DELETE를 날린다. 지금은 스무 행 남짓이라 차이가 없지만, 이 표는 "통째로 갈아
     * 끼운다"는 것이 설계이므로 그 의도대로 한 문장으로 끝낸다.
     *
     * <p>부분 갱신(upsert)을 하지 않는 이유는 <b>사라진 항목</b> 때문이다. 어제 순위에 있던
     * 카테고리가 오늘 문턱 아래로 내려가면 upsert로는 그 행이 그대로 남아, 이미 기준을 못
     * 넘긴 항목이 계속 "인기"로 보인다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PickTrend t")
    void deleteAllTrends();
}
