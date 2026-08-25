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

/**
 * {@code memo}의 상한 1000자 근거: 컬럼은 {@code TEXT}라 한계가 문자 수가 아니라
 * <b>65,535바이트</b>다. utf8mb4에서 한글은 3바이트, 이모지는 4바이트를 먹으므로 실제로는
 * 약 1.6만~2.1만 자에서 넘치고, 그때는 검증을 통과한 요청이 DB까지 내려가 409로 죽으면서
 * 어느 필드가 문제인지도 응답에 담기지 않는다({@code RestaurantRequest} javadoc과 같은 이유).
 * 1000자면 문자당 4바이트인 최악의 경우에도 4,000바이트라 컬럼 한계에 닿을 수 없고,
 * 한 줄 메모라는 용도에도 충분하다.
 */
public class MenuRequest {

    public record Create(
            @NotBlank(message = "메뉴 이름은 필수입니다.")
            @Size(max = 100)
            String name,
            @Size(max = 1000, message = "메모는 1000자 이하여야 합니다.")
            String memo,
            @Min(1) @Max(5)
            int weight,
            // 컬렉션 원소 단위 검증 — 빈 문자열/공백, 20자 초과 카테고리를 DB(VARCHAR(20)) 도달 전에 차단한다.
            // 검증은 trim 이전 원본 값 기준이며, 저장 직전 trim은 MenuService가 수행한다.
            // 컬렉션 크기 상한. PickRequest가 같은 문제를 인지하고 세 집합 전부에 걸어 둔 것을
            // 여기에도 맞춘다. 없으면 categories에 5만 개를 담은 요청 하나가 한 트랜잭션에서
            // menu_categories에 5만 행을 INSERT한다.
            @Size(max = 20, message = "카테고리는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotBlank(message = "카테고리는 비어 있을 수 없습니다.")
                @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.") String> categories,
            @Size(max = 20, message = "태그는 최대 20개까지 지정할 수 있습니다.")
            Set<Long> tagIds
    ) {}

    public record Update(
            @NotBlank(message = "메뉴 이름은 필수입니다.")
            @Size(max = 100)
            String name,
            @Size(max = 1000, message = "메모는 1000자 이하여야 합니다.")
            String memo,
            @Min(1) @Max(5)
            int weight,
            // PUT은 전체 교체 계약이므로 필드 누락을 허용하지 않는다. primitive boolean이면 누락 시
            // 조용히 false로 채워져 기존 제외 상태가 풀리므로(이슈 #7) Boolean + @NotNull로 400을 강제한다.
            @NotNull(message = "제외 여부(isExcluded)는 필수입니다.")
            Boolean isExcluded,
            // 상한 근거는 Create의 같은 필드 주석 참고.
            @Size(max = 20, message = "카테고리는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotBlank(message = "카테고리는 비어 있을 수 없습니다.")
                @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.") String> categories,
            @Size(max = 20, message = "태그는 최대 20개까지 지정할 수 있습니다.")
            Set<Long> tagIds
    ) {}

    public record BatchUpdateWeight(
            @NotEmpty(message = "가중치 변경 목록은 필수입니다.")
            @Size(max = 100, message = "한 번에 100개까지만 변경할 수 있습니다.")
            // 원소의 @NotNull이 없으면 [null]이 검증을 그대로 통과한다 — @Valid는 null 원소를
            // 검증 대상에서 제외하기 때문이다. 그러면 서비스에서 entry.menuId()가 NPE를 내고
            // catch-all이 500 + 풀 스택트레이스를 남긴다. 잘못된 요청은 400이어야 한다.
            List<@NotNull(message = "가중치 항목은 비어 있을 수 없습니다.") @Valid WeightEntry> entries
    ) {}

    public record WeightEntry(
            @NotNull(message = "메뉴 ID는 필수입니다.")
            Long menuId,
            @Min(1) @Max(5)
            int weight
    ) {}
}
