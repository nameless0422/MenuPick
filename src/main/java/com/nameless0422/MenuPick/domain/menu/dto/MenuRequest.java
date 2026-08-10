package com.nameless0422.MenuPick.domain.menu.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public class MenuRequest {

    public record Create(
            @NotBlank(message = "메뉴 이름은 필수입니다.")
            @Size(max = 100)
            String name,
            String memo,
            @Min(1) @Max(5)
            int weight,
            // 컬렉션 원소 단위 검증 — 빈 문자열/공백, 20자 초과 카테고리를 DB(VARCHAR(20)) 도달 전에 차단한다.
            // 검증은 trim 이전 원본 값 기준이며, 저장 직전 trim은 MenuService가 수행한다.
            Set<@NotBlank(message = "카테고리는 비어 있을 수 없습니다.")
                @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.") String> categories,
            Set<Long> tagIds
    ) {}

    public record Update(
            @NotBlank(message = "메뉴 이름은 필수입니다.")
            @Size(max = 100)
            String name,
            String memo,
            @Min(1) @Max(5)
            int weight,
            // PUT은 전체 교체 계약이므로 필드 누락을 허용하지 않는다. primitive boolean이면 누락 시
            // 조용히 false로 채워져 기존 제외 상태가 풀리므로(이슈 #7) Boolean + @NotNull로 400을 강제한다.
            @NotNull(message = "제외 여부(isExcluded)는 필수입니다.")
            Boolean isExcluded,
            Set<@NotBlank(message = "카테고리는 비어 있을 수 없습니다.")
                @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.") String> categories,
            Set<Long> tagIds
    ) {}

    public record BatchUpdateWeight(
            @NotEmpty(message = "가중치 변경 목록은 필수입니다.")
            List<@Valid WeightEntry> entries
    ) {}

    public record WeightEntry(
            @NotNull(message = "메뉴 ID는 필수입니다.")
            Long menuId,
            @Min(1) @Max(5)
            int weight
    ) {}
}
