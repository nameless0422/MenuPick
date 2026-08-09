package com.nameless0422.MenuPick.domain.menu.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public class MenuResponse {

    public record MenuSummary(
            Long id,
            String name,
            int weight,
            boolean isExcluded,
            Set<String> categories,
            List<TagSummary> tags
    ) {}

    public record MenuDetail(
            Long id,
            String name,
            String memo,
            int weight,
            boolean isExcluded,
            Set<String> categories,
            List<TagSummary> tags,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record MenuListResponse(
            List<MenuSummary> menus,
            Long nextCursor,
            boolean hasNext
    ) {}

    public record TagSummary(Long id, String name) {}
}
