package com.nameless0422.MenuPick.domain.pick.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record PickPreferenceRequest(
        @NotNull @Size(max = 50) Set<Long> defaultExcludedTagIds
) {}
