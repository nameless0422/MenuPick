package com.nameless0422.MenuPick.domain.tag.dto;

import java.time.LocalDateTime;

public class TagResponse {

    public record TagInfo(Long id, String name, LocalDateTime createdAt) {}
}
