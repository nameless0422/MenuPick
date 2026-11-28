package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.history.MealSlot;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 지금 끼니에 자주 쓴 빠른 픽을 하나 고른다(D-095).
 *
 * <p>목록 순서를 바꾸지 않고 <b>하나만</b> 제안한다. 매번 순서가 바뀌면 손이 기억한 자리가
 * 어긋난다. 근거가 모자라면(같은 끼니에 {@link #MIN_USES}번 미만) 아무것도 제안하지 않는다 —
 * 한 번 쓴 것을 "자주"라고 부르면 제안이 우연을 따라다닌다.
 *
 * <p>끼니는 {@link MealSlot#at}으로 본다. 직접 기록이 쓰는 그 경계와 같아야 "점심"이 화면마다
 * 다른 시간을 가리키지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PickPresetSuggestionService {

    /** 최근 이만큼만 본다. 계절·이직으로 습관이 바뀌면 옛 기록이 오래 붙들지 않게 한다. */
    public static final int WINDOW_DAYS = 60;

    public static final int MIN_USES = 2;

    private final HistoryRepository historyRepository;
    private final Clock clock;

    public PickPresetResponse.Suggestion suggest(Long userId, List<PickPresetResponse.Detail> presets) {
        // 실행할 수 없는 빠른 픽은 제안하지 않는다. 지워진 빠른 픽의 기록도 여기서 걸러진다.
        Set<Long> runnable = presets.stream()
                .filter(p -> !p.needsReview())
                .map(PickPresetResponse.Detail::id)
                .collect(Collectors.toSet());
        if (runnable.isEmpty()) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        MealSlot slot = MealSlot.at(now.toLocalTime());

        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, LocalDateTime> lastUsed = new HashMap<>();
        for (HistoryRepository.PresetUse use
                : historyRepository.findPresetUsesSince(userId, now.minusDays(WINDOW_DAYS))) {
            if (!runnable.contains(use.getPresetId())
                    || MealSlot.at(use.getRecommendedAt().toLocalTime()) != slot) {
                continue;
            }
            counts.merge(use.getPresetId(), 1, Integer::sum);
            lastUsed.merge(use.getPresetId(), use.getRecommendedAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }

        // 횟수가 같으면 최근에 쓴 쪽. 그래도 같으면 id가 작은 쪽 — 응답이 매번 같아야 한다.
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_USES)
                .max((a, b) -> {
                    int byCount = Integer.compare(a.getValue(), b.getValue());
                    if (byCount != 0) return byCount;
                    int byRecent = lastUsed.get(a.getKey()).compareTo(lastUsed.get(b.getKey()));
                    if (byRecent != 0) return byRecent;
                    return Long.compare(b.getKey(), a.getKey());
                })
                .map(e -> new PickPresetResponse.Suggestion(e.getKey(), slot.name(), e.getValue(), WINDOW_DAYS))
                .orElse(null);
    }
}
