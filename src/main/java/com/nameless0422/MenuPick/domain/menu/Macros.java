package com.nameless0422.MenuPick.domain.menu;

import java.math.BigDecimal;

/**
 * 1인분 영양소 추정치. 탄수화물·단백질·지방·식이섬유(g, D-084)와 당류(g)·나트륨(mg, D-085).
 * 각 값은 모르면 null이다 — 0과 다르다.
 *
 * <p>탄단지는 셋이 함께 있을 때만 "안다"고 본다. 하나만 있으면 열량 비율을 그릴 수 없다.
 * 식이섬유·당류·나트륨은 원본 데이터에도 빠진 음식이 있어 하나씩 따로 센다.
 */
public record Macros(BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG,
                     BigDecimal sugarsG, Integer sodiumMg) {

    public static final Macros UNKNOWN = new Macros(null, null, null, null, null, null);

    /** 당류·나트륨 전 호출부 호환. */
    public Macros(BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG) {
        this(carbsG, proteinG, fatG, fiberG, null, null);
    }

    public boolean hasMacros() {
        return carbsG != null && proteinG != null && fatG != null;
    }

    /** 하나라도 적혀 있는가. 열량 없이 영양소만 둘 수 없다는 검증에 쓴다. */
    public boolean hasAny() {
        return carbsG != null || proteinG != null || fatG != null || fiberG != null
                || sugarsG != null || sodiumMg != null;
    }
}
