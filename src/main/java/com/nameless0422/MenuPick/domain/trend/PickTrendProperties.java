package com.nameless0422.MenuPick.domain.trend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 집단 통계 설정.
 *
 * <p>세 값 모두 <b>운영 중에 조정할 일이 생기는</b> 값이라 상수가 아니라 설정으로 뺐다.
 * 특히 {@code minUsers}는 사용자가 늘어나는 속도에 따라 바로 손대야 하는데, 상수면 그때마다
 * 이미지를 다시 빌드해 재배포해야 한다({@code DB_MAX_POOL_SIZE}를 뺀 것과 같은 이유다).
 *
 * @param enabled    교차 사용자 통계를 켜는 명시적 rollout 스위치. 기본은 꺼짐이다.
 * @param windowDays 집계 기간(일). 7일인 이유는 최근 선택·방문 경향이 이 기능이
 *                   답하려는 질문이기 때문이다. 짧으면 표본이 안 모이고, 길면 "최근"이 아니라
 *                   "전부"가 되어 순위가 굳는다 — 매일 같은 화면을 보게 된다.
 * @param minUsers   이 수 미만인 항목은 <b>저장하지 않는다.</b> 두 가지를 동시에 막는다.
 *                   (1) 소수 집계 노출 위험을 낮춘다. (2) 한 사람의 반복 동작을 집단 경향으로
 *                   과장하지 않는다. 이 문턱만으로 익명성이 보장되지는 않는다.
 * @param maxLabels  한 축에서 응답할 최대 항목 수(1..20).
 */
@ConfigurationProperties(prefix = "trends")
public record PickTrendProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("7") int windowDays,
        @DefaultValue("5") int minUsers,
        @DefaultValue("10") int maxLabels
) {
    public PickTrendProperties {
        if (windowDays < 1 || windowDays > 30) {
            throw new IllegalArgumentException("trends.window-days는 1..30이어야 한다: " + windowDays);
        }
        // 1이면 한 명의 선택·방문 기록이 그대로 집단 순위가 된다.
        if (minUsers < 5 || minUsers > 100_000) {
            throw new IllegalArgumentException("trends.min-users는 5..100000이어야 한다: " + minUsers);
        }
        if (maxLabels < 1 || maxLabels > 20) {
            throw new IllegalArgumentException("trends.max-labels는 1..20이어야 한다: " + maxLabels);
        }
    }
}
