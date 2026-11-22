package com.nameless0422.MenuPick.domain.nutrition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 기준 음식 한 줄 — 식약처 식품영양성분 DB "음식" 데이터의 사본(D-082).
 *
 * <p>앱은 읽기만 한다. 행은 {@code scripts/import-food-references.py}가 코드 기준으로 넣고 고친다.
 * 원본에서 빠진 행은 메뉴가 가리키지 않을 때만 지운다(D-090). 모든 값은 1인분 기준으로 환산돼 있다.
 */
@Entity
@Table(name = "food_references")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FoodReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String category;

    /** 1인분 양({@link #servingUnit} 단위). 원본에 없으면 null. */
    private BigDecimal servingGrams;

    /** 1인분 양의 단위 — "g" 또는 "ml"(D-090). */
    @Column(nullable = false, length = 2)
    private String servingUnit = "g";

    @Column(nullable = false)
    private int kcalPerServing;

    // 이름을 적는 이유: 대문자 한 글자로 끝나는 필드(carbsG)는 기본 이름 전략이 carbsg로 바꾼다.
    @Column(name = "carbs_g")
    private BigDecimal carbsG;
    @Column(name = "protein_g")
    private BigDecimal proteinG;
    @Column(name = "fat_g")
    private BigDecimal fatG;
    @Column(name = "fiber_g")
    private BigDecimal fiberG;
    @Column(name = "sugars_g")
    private BigDecimal sugarsG;
    @Column(name = "sodium_mg")
    private Integer sodiumMg;

    /** 원본 데이터의 생성일. */
    private LocalDate sourceDate;

    @Column(nullable = false)
    private LocalDateTime importedAt;

    /** 테스트 픽스처용. 운영 데이터는 가져오기 스크립트만 만든다. */
    public static FoodReference of(String code, String name, String category, BigDecimal servingGrams,
                                   int kcalPerServing, LocalDateTime importedAt) {
        FoodReference reference = new FoodReference();
        reference.code = code;
        reference.name = name;
        reference.category = category;
        reference.servingGrams = servingGrams;
        reference.kcalPerServing = kcalPerServing;
        reference.importedAt = importedAt;
        return reference;
    }

    /** 테스트 픽스처용. */
    public FoodReference withMacros(BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG) {
        this.carbsG = carbsG;
        this.proteinG = proteinG;
        this.fatG = fatG;
        this.fiberG = fiberG;
        return this;
    }

    /** 테스트 픽스처용. */
    public FoodReference withSugarsAndSodium(BigDecimal sugarsG, Integer sodiumMg) {
        this.sugarsG = sugarsG;
        this.sodiumMg = sodiumMg;
        return this;
    }

    /** 테스트 픽스처용. */
    public FoodReference withServingUnit(String servingUnit) {
        this.servingUnit = servingUnit;
        return this;
    }

    /**
     * 값의 출처 구분. 식품코드 둘째 자리가 원본의 식품기원코드다 — 1 가정식, 2 프랜차이즈 등 업체 제공,
     * 3·4 외식(분석·재료량 산출), 5·6 초·중고등학교 급식, 7 산업체 급식. 모르는 코드면 null.
     *
     * <p>급식은 아이 몫이거나 국물 위주라 같은 "김치찌개"라도 외식의 1/6 열량이 나온다. 화면에 함께 보여
     * 사용자가 고를 수 있게 하고, 검색은 외식 → 가정식 → 프랜차이즈 → 급식 순으로 둔다.
     */
    public String origin() {
        if (code == null || code.length() < 2 || code.charAt(0) != 'D') return null;
        return switch (code.charAt(1)) {
            case '1' -> "가정식";
            case '2' -> "프랜차이즈";
            case '3', '4' -> "외식";
            case '5' -> "초등 급식";
            case '6' -> "중고등 급식";
            case '7' -> "단체 급식";
            default -> null;
        };
    }

    public com.nameless0422.MenuPick.domain.menu.Macros macros() {
        return new com.nameless0422.MenuPick.domain.menu.Macros(carbsG, proteinG, fatG, fiberG, sugarsG, sodiumMg);
    }
}
