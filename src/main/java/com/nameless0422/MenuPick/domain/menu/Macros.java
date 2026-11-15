package com.nameless0422.MenuPick.domain.menu;

import java.math.BigDecimal;

/**
 * 1인분 탄수화물·단백질·지방·식이섬유(g) 추정치(D-084). 각 값은 모르면 null이다 — 0과 다르다.
 *
 * <p>탄단지는 셋이 함께 있을 때만 "안다"고 본다. 하나만 있으면 열량 비율을 그릴 수 없다.
 * 식이섬유는 원본 데이터에도 빠진 음식이 많아 따로 센다.
 */
public record Macros(BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG) {

    public static final Macros UNKNOWN = new Macros(null, null, null, null);

    public boolean hasMacros() {
        return carbsG != null && proteinG != null && fatG != null;
    }
}
