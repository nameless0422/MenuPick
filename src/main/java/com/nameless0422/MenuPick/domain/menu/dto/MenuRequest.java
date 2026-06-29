package com.nameless0422.MenuPick.domain.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public class MenuRequest {

    public record Create(
            @NotBlank(message = "메뉴 이름은 필수입니다.")
            @Size(max = 100)
            String name,
            String memo,
            @Min(1) @Max(5)
            int weight,
            Set<String> categories,
            Set<Long> tagIds
    ) {}

    public record Update(
            @NotBlank(message = "메뉴 이름은 필수입니다.")
            @Size(max = 100)
            String name,
            String memo,
            @Min(1) @Max(5)
            int weight,
            boolean isExcluded,
            Set<String> categories,
            Set<Long> tagIds
    ) {}

    public record BatchUpdateWeight(
            @jakarta.validation.constraints.NotEmpty(message = "가중치 변경 목록은 필수입니다.")
            java.util.List<WeightEntry> entries
    ) {}

    public record WeightEntry(
            Long menuId,
            @Min(1) @Max(5)
            int weight
    ) {}
}
