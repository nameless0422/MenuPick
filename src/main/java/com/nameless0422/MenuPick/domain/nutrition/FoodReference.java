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
 * <p>앱은 읽기만 한다. 행은 {@code scripts/import-food-references.py}가 코드 기준으로 넣고 고치며
 * 지우지 않는다. 모든 값은 1인분 기준으로 환산돼 있다.
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

    /** 1인분 무게(g 또는 ml). 원본에 없으면 null. */
    private BigDecimal servingGrams;

    @Column(nullable = false)
    private int kcalPerServing;

    // 이름을 적는 이유: 대문자 한 글자로 끝나는 필드(carbsG)는 기본 이름 전략이 carbsg로 바꾼다.
    @Column(name = "carbs_g")
    private BigDecimal carbsG;
    @Column(name = "protein_g")
    private BigDecimal proteinG;
    @Column(name = "fat_g")
    private BigDecimal fatG;

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
}
