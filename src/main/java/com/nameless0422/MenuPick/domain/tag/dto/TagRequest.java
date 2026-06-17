package com.nameless0422.MenuPick.domain.tag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TagRequest {

    public record Create(
            @NotBlank(message = "태그 이름은 필수입니다.")
            @Size(max = 50)
            String name
    ) {}
}
