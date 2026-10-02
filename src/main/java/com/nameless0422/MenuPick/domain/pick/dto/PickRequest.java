package com.nameless0422.MenuPick.domain.pick.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 랜덤 픽 필터 요청.
 *
 * <p>모든 필드가 선택 값이다 — 본문 자체가 없으면(required=false) 검증도 수행되지 않고
 * 필터 없는 전체 후보 픽으로 동작한다. 값이 들어온 경우에만 아래 범위 제약이 적용된다.
 *
 * <p><b>컬렉션 제약이 필요한 이유</b>: PickService.saveHistory가 요청의 필터 조건을 하나도
 * 빠짐없이 {@code history_filter_conditions}에 INSERT한다({@code filter_value VARCHAR(100)}).
 * 카테고리 필터는 {@code disjoint} 검사라 원소 하나만 맞아도 후보가 남으므로, 100자를 넘는
 * 원소가 섞여 있어도 픽 자체는 성공한 뒤 히스토리 INSERT에서
 * {@code Data too long for column 'filter_value'}로 <b>픽 전체가 롤백되고 409</b>가 나간다.
 * 사용자 입장에서는 정상 요청인데 원인을 알 수 없는 오류를 받는 셈이라, DB에 닿기 전에
 * 400 + 필드명으로 돌려준다.
 *
 * <p>집합 크기 상한(20)은 한 번의 픽이 히스토리 행을 무제한으로 만들어 내지 못하게 막는 것이다.
 * 세 집합이 각각 20개면 픽 한 번이 남기는 필터 행은 최대 61개(카테고리 20 + 포함 태그 20 +
 * 제외 태그 20 + 최대거리 1 + 최근 제외 1)로 묶인다. 실사용에서도 이보다 많은 조건은 의미가 없다 —
 * 포함 태그는 {@code containsAll} 조건이라 개수가 늘수록 후보가 0에 수렴한다.
 */
public record PickRequest(
        // 원소 상한 20자는 MenuRequest의 카테고리 제약(= menu_categories.category VARCHAR(20))과 같은 값이다.
        // 저장된 카테고리보다 긴 값은 어차피 어떤 메뉴와도 매칭되지 않으므로 받을 이유가 없다.
        @Size(max = 20, message = "카테고리는 최대 20개까지 지정할 수 있습니다.")
        Set<@NotBlank(message = "카테고리는 비어 있을 수 없습니다.")
            @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.") String> categories,
        @Size(max = 20, message = "포함 태그는 최대 20개까지 지정할 수 있습니다.")
        Set<Long> tagIds,
        @Size(max = 20, message = "제외 태그는 최대 20개까지 지정할 수 있습니다.")
        Set<Long> excludeTagIds,
        @DecimalMin(value = "-90", message = "위도는 -90 이상이어야 합니다.")
        @DecimalMax(value = "90", message = "위도는 90 이하여야 합니다.")
        BigDecimal latitude,
        @DecimalMin(value = "-180", message = "경도는 -180 이상이어야 합니다.")
        @DecimalMax(value = "180", message = "경도는 180 이하여야 합니다.")
        BigDecimal longitude,
        @Positive(message = "최대 거리는 0보다 커야 합니다.")
        Integer maxDistance,
        /**
         * 최근 이 기간(일) 안에 뽑힌 메뉴를 후보에서 뺀다. null이면 빼지 않는다.
         *
         * <p>상한 30일은 임의로 고른 숫자가 아니다. 판정에 쓰는 "메뉴별 마지막 추천 시각"은
         * 픽이 어차피 읽는 최근 30일치 집계({@code PickCandidateEvaluator.FEEDBACK_WINDOW_DAYS})를
         * 그대로 재사용한다 — 그래서 이 조건은 <b>조회를 한 건도 더 늘리지 않는다.</b> 31일을
         * 허용하면 그 집계 밖을 묻는 셈이라, 31일 전에 뽑힌 메뉴가 "최근 아님"으로 조용히
         * 통과한다. 지킬 수 없는 약속을 받지 않으려고 경계에서 막는다.
         */
        @Min(value = 1, message = "최근 제외 기간은 1일 이상이어야 합니다.")
        @Max(value = 30, message = "최근 제외 기간은 30일 이하여야 합니다.")
        Integer excludeRecentDays
) {

    /**
     * 최근 제외가 없는 6개 인자 요청.
     *
     * <p>프리셋({@code PickPresetService})은 저장된 조건만으로 요청을 만들고, 그 조건에는
     * 최근 제외가 없다 — 칼럼을 더하려면 마이그레이션이 필요하고, 이 조건은 "오늘 이 한 번"의
     * 성격이라 저장할 값이 아니다. 호출부마다 {@code null}을 적게 하는 대신 뜻을 이름으로
     * 남긴다.
     */
    public PickRequest(Set<String> categories, Set<Long> tagIds, Set<Long> excludeTagIds,
            BigDecimal latitude, BigDecimal longitude, Integer maxDistance) {
        this(categories, tagIds, excludeTagIds, latitude, longitude, maxDistance, null);
    }
}
