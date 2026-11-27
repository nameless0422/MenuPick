package com.nameless0422.MenuPick.domain.pick;

import java.util.Set;

/**
 * 프리셋이 저장할 수 있는 1인분 열량 상한.
 *
 * <p>{@link PickPresetDistance}와 같은 이유로 자유 입력을 받지 않는다 — 픽 화면의 "1인분 열량"
 * 버튼(500/700/900)과 같은 단계만 저장한다. 일반 {@code POST /pick}의 {@code maxKcal}은
 * 100~5,000 범위의 자유 값 그대로다.
 *
 * <p>{@code null}은 "열량 조건 없음"이고 유효한 값이다.
 */
public final class PickPresetKcal {

    public static final Set<Integer> ALLOWED = Set.of(500, 700, 900);

    private PickPresetKcal() {
    }

    public static boolean isValid(Integer maxKcal) {
        return maxKcal == null || ALLOWED.contains(maxKcal);
    }
}
