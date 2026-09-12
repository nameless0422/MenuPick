package com.nameless0422.MenuPick.domain.pick;

import java.util.List;
import java.util.Set;

/**
 * 프리셋이 저장할 수 있는 거리 값.
 *
 * <p>자유 입력을 받지 않고 네 단계로 고정한다. 프리셋은 "빠른 픽"이고 고르는 화면이 버튼
 * 몇 개이기 때문이다 — 1,137m 같은 값을 저장할 자리가 UI에 없고, 있다 해도 다음에 그 숫자를
 * 보고 무엇을 의도했는지 알기 어렵다. 일반 {@code POST /pick}의 {@code maxDistance}는
 * 그대로 자유 입력이다(그쪽은 슬라이더가 만든 값을 그대로 쓴다).
 *
 * <p>{@code null}은 "거리 조건 없음"이고 유효한 값이다 — 0이나 특별한 상수로 대신하지 않는다.
 */
public final class PickPresetDistance {

    /** 도보 5분 / 10분 / 15분, 그리고 차로 잠깐. */
    public static final Set<Integer> ALLOWED = Set.of(300, 500, 1000, 2000);

    /** 오류 메시지에 쓸 정렬된 표현. Set은 순서를 보장하지 않는다. */
    public static final List<Integer> ALLOWED_SORTED = List.of(300, 500, 1000, 2000);

    private PickPresetDistance() {
    }

    /** {@code null}(거리 조건 없음)도 유효하다. */
    public static boolean isValid(Integer maxDistance) {
        return maxDistance == null || ALLOWED.contains(maxDistance);
    }
}
