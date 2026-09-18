package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 식사 기록 요약 — "요즘 뭘 먹고 살았나".
 *
 * <h2>집단 통계와 무엇이 다른가</h2>
 *
 * <p>{@code domain/trend}의 집단 통계는 <b>남들</b>이 뭘 먹었는지를 세고, 그래서 최소 인원
 * 문턱·닫힌 어휘·하루 한 번 집계라는 장치가 전부 필요하다. 여기는 <b>내</b> 기록이라 그 장치가
 * 하나도 필요 없다. 표본이 한 건이어도 내 한 건이고, 내가 지은 메뉴 이름이어도 나는 그게
 * 무엇인지 안다. 요청할 때 바로 세어도 되는 이유다(내 행만 보므로 범위가 작다).
 *
 * <h2>"먹었다"의 기준은 집단 통계와 같게 둔다</h2>
 *
 * <p>방문 처리했거나 추천을 수락했거나. 두 곳이 다른 기준을 쓰면 같은 사람이 두 화면에서
 * 서로 어긋나는 숫자를 보게 된다.
 *
 * <h2>잊힌 메뉴는 기간을 보지 않는다</h2>
 *
 * <p>나머지는 "최근 N일"인데 이것만 전 기간이다. 묻는 질문이 "이번 달에 안 먹은 것"이 아니라
 * <b>"마지막으로 먹은 게 언제인가"</b>이기 때문이다. 기간으로 자르면 6개월 전에 먹은 메뉴와
 * 한 번도 안 먹은 메뉴가 똑같이 "30일간 안 먹음"으로 뭉개진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EatingSummaryService {

    /** 기본 집계 기간. 픽 개인화 보정이 보는 창(30일)과 같게 둔다. */
    public static final int DEFAULT_DAYS = 30;

    /** 기간 상한. 전 구간 집계를 매 요청 돌리지 않도록 막는다. */
    public static final int MAX_DAYS = 365;

    /** 한 목록에 보여줄 항목 수. 화면이 읽는 것이지 분석하는 것이 아니라 다섯이면 충분하다. */
    private static final int TOP_LIMIT = 5;

    private final HistoryRepository historyRepository;
    private final Clock clock;

    public HistoryResponse.EatingSummaryResponse summarize(Long userId, Integer requestedDays) {
        int days = normalizeDays(requestedDays);
        LocalDateTime since = LocalDateTime.now(clock).minusDays(days);
        PageRequest top = PageRequest.of(0, TOP_LIMIT);

        // 픽 수와 먹은 수. 비율로 줄이지 않는다 — "10번 뽑아 3번 먹었다"와 "3번 뽑아 3번"은
        // 같은 30%지만 사용자에게는 완전히 다른 이야기다.
        Object[] totals = historyRepository
                .countMyPicksAndEatenSince(userId, since, RecommendationFeedback.ACCEPTED)
                .get(0);
        long picks = toLong(totals[0]);
        long eaten = toLong(totals[1]);

        List<HistoryResponse.LabelCount> categories = historyRepository
                .countMyEatenByCategorySince(userId, since, RecommendationFeedback.ACCEPTED, top)
                .stream()
                .map(row -> new HistoryResponse.LabelCount(row.getLabel(), row.getTotal()))
                .toList();

        List<HistoryResponse.LabelCount> menus = historyRepository
                .countMyEatenByMenuSince(userId, since, RecommendationFeedback.ACCEPTED, top)
                .stream()
                .map(row -> new HistoryResponse.LabelCount(row.getLabel(), row.getTotal()))
                .toList();

        List<HistoryResponse.ForgottenMenu> forgotten = historyRepository
                .findMyForgottenMenus(userId, top)
                .stream()
                .map(row -> new HistoryResponse.ForgottenMenu(
                        row.getMenuId(), row.getName(), row.getLastPickedAt()))
                .toList();

        return new HistoryResponse.EatingSummaryResponse(days, picks, eaten, categories, menus, forgotten);
    }

    /**
     * 범위 밖 값은 거절하지 않고 기본값으로 되돌린다.
     *
     * <p>이 화면은 요약이지 정밀한 질의가 아니다. 사용자가 주소창의 숫자를 고쳐 10000을 넣었을
     * 때 400을 내밀기보다 30일치를 보여주는 편이 낫다. 다만 상한이 없으면 전 구간 집계가
     * 매 요청 돌아가므로 자르기는 한다. ({@code KpiEndpoint}가 같은 방식을 쓴다.)
     */
    private int normalizeDays(Integer requestedDays) {
        if (requestedDays == null || requestedDays < 1 || requestedDays > MAX_DAYS) {
            return DEFAULT_DAYS;
        }
        return requestedDays;
    }

    /** {@code count()}는 Long, {@code sum()}은 표본이 없으면 null이다. */
    private long toLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
