package com.nameless0422.MenuPick.domain.history;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code Specification.md} 8장의 추천 품질 KPI 3종을 실제로 계산한다.
 *
 * <h2>왜 이제야 만드는가</h2>
 *
 * <p>세 지표는 정의와 목표치(30% / 20% / 25%)만 문서에 있고 <b>계산하는 코드가 없었다.</b>
 * 그래서 "달성했는가"를 물으면 아무도 답할 수 없었다. 2026-09-06~08에 추천 피드백과
 * 개인화 보정이 들어갔는데, 그것이 효과가 있는지 판정할 수단이 바로 이 지표들이다.
 *
 * <h2>비율만 주지 않는다</h2>
 *
 * <p>모든 지표가 분자·분모를 함께 돌려준다. 비율만 있으면 <b>"0%"와 "표본이 없음"을 구분할 수
 * 없고</b>, 표본이 3건일 때의 33%를 목표 달성으로 읽게 된다. 분모가 0이면 비율은
 * {@code null}이다 — 0.0으로 채우면 "나쁨"으로 오독된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KpiService {

    /** 첫 픽 후 이 기간 안에 다시 오면 "유지"로 본다(Specification.md 8장). */
    private static final int RETENTION_DAYS = 7;

    private final HistoryRepository historyRepository;
    private final Clock clock;

    /**
     * @param lookbackDays 방문율·재픽률을 집계할 기간(일). 리텐션은 아래 주석대로 별도 규칙을 쓴다.
     */
    public Map<String, Object> collect(int lookbackDays) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(lookbackDays);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", now.toString());
        result.put("lookbackDays", lookbackDays);
        result.put("visitRate", visitRate(since));
        result.put("sameDayRepickRate", sameDayRepickRate(since));
        result.put("sevenDayRetention", sevenDayRetention(now));
        return result;
    }

    /** 픽 후 방문율 — 추천받은 메뉴를 실제 방문 처리한 비율. 목표 ≥ 30%. */
    private Map<String, Object> visitRate(LocalDateTime since) {
        Object[] row = first(historyRepository.countPicksAndVisitsSince(since));
        long picks = toLong(row[0]);
        long visited = toLong(row[1]);
        return metric("picks", picks, "visited", visited, ratio(visited, picks), 0.30, true);
    }

    /**
     * 재픽률 — 같은 날 픽을 다시 요청한 사용자 비율. 목표 ≤ 20%로 <b>낮을수록 좋다.</b>
     *
     * <p>"사용자 비율"을 기간 내 픽한 사용자 중 <b>어느 하루라도 두 번 이상 픽한 사용자</b>의
     * 비율로 읽는다. 기간이 여러 날이므로 사용자·날짜 쌍이 아니라 사용자로 세지 않으면
     * 기간이 길어질수록 분모가 부풀어 지표가 저절로 좋아 보인다.
     */
    private Map<String, Object> sameDayRepickRate(LocalDateTime since) {
        long users = historyRepository.countDistinctUsersSince(since);
        long repicked = historyRepository.countUsersWithSameDayRepick(since);
        return metric("users", users, "repickedUsers", repicked, ratio(repicked, users), 0.20, false);
    }

    /**
     * 7일 리텐션 — 첫 픽 후 7일 내에 다시 픽한 사용자 비율. 목표 ≥ 25%.
     *
     * <h3>코호트에서 빼야 하는 사람들</h3>
     *
     * <p><b>첫 픽이 7일도 안 지난 사용자는 분모에 넣지 않는다.</b> 넣으면 아직 돌아올 시간이
     * 남아 있는 사람을 "안 돌아온 사람"으로 세어, 신규 가입이 늘수록 리텐션이 떨어지는
     * 착시가 생긴다. 어제 가입한 사용자는 7일이 지난 뒤에야 이 지표에 등장한다.
     *
     * <p>그래서 이 지표에는 {@code lookbackDays}를 적용하지 않는다. 기간을 자르면 코호트가
     * "그 기간에 첫 픽을 한 사람"이 되는데, 기간 끝자락의 사용자는 방금 말한 이유로 다시
     * 빠져야 해서 경계가 두 겹이 된다. 전체 사용자를 대상으로 하되 7일이 지난 사람만 센다.
     */
    private Map<String, Object> sevenDayRetention(LocalDateTime now) {
        LocalDateTime cohortCutoff = now.minusDays(RETENTION_DAYS);

        List<Long> cohort = new ArrayList<>();
        Map<Long, LocalDateTime> firstPickOf = new LinkedHashMap<>();
        for (Object[] row : historyRepository.findFirstPickPerUser()) {
            Long userId = toLong(row[0]);
            LocalDateTime firstPick = (LocalDateTime) row[1];
            if (firstPick != null && !firstPick.isAfter(cohortCutoff)) {
                cohort.add(userId);
                firstPickOf.put(userId, firstPick);
            }
        }

        if (cohort.isEmpty()) {
            Map<String, Object> empty = metric("cohort", 0, "retained", 0, null, 0.25, true);
            empty.put("note", "첫 픽 후 " + RETENTION_DAYS + "일이 지난 사용자가 아직 없다");
            return empty;
        }

        List<Long> retained = new ArrayList<>();
        Map<Long, Boolean> seen = new LinkedHashMap<>();
        for (Object[] row : historyRepository.findPickTimesForUsers(cohort)) {
            Long userId = toLong(row[0]);
            LocalDateTime at = (LocalDateTime) row[1];
            LocalDateTime firstPick = firstPickOf.get(userId);
            if (firstPick == null || at == null) continue;
            // 첫 픽 자체는 재방문이 아니다. 같은 시각의 행을 포함시키면 픽을 한 번만 한
            // 사용자도 전원 "유지"로 잡혀 지표가 항상 100%가 된다.
            boolean isReturn = at.isAfter(firstPick)
                    && !at.isAfter(firstPick.plusDays(RETENTION_DAYS));
            if (isReturn && seen.putIfAbsent(userId, true) == null) {
                retained.add(userId);
            }
        }

        return metric("cohort", cohort.size(), "retained", retained.size(),
                ratio(retained.size(), cohort.size()), 0.25, true);
    }

    /**
     * @param higherIsBetter 목표를 넘어야 좋은 지표인지. 재픽률만 반대라, 값을 읽는 쪽이
     *                       매번 어느 방향인지 기억하지 않아도 되도록 함께 실어 보낸다.
     */
    private static Map<String, Object> metric(String denominatorName, long denominator,
                                              String numeratorName, long numerator,
                                              Double rate, double target, boolean higherIsBetter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(denominatorName, denominator);
        m.put(numeratorName, numerator);
        m.put("rate", rate);
        m.put("target", target);
        m.put("higherIsBetter", higherIsBetter);
        m.put("meetsTarget", rate == null ? null : (higherIsBetter ? rate >= target : rate <= target));
        return m;
    }

    /** 분모가 0이면 {@code null}. 0.0으로 채우면 "표본 없음"이 "성과 나쁨"으로 읽힌다. */
    private static Double ratio(long numerator, long denominator) {
        if (denominator <= 0) return null;
        return Math.round((double) numerator / denominator * 10_000d) / 10_000d;
    }

    private static Object[] first(List<Object[]> rows) {
        // count 집계는 행이 없어도 한 줄을 돌려주지만, sum은 그 줄에서 null이 될 수 있다.
        return rows.isEmpty() ? new Object[]{0L, 0L} : rows.get(0);
    }

    private static long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** 테스트가 상수를 문자열로 베끼지 않도록 노출한다. */
    static Duration retentionWindow() {
        return Duration.ofDays(RETENTION_DAYS);
    }
}
