package com.nameless0422.MenuPick.domain.menu.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public class MenuResponse {

    public record DeletedMenuSummary(Long id, String name, LocalDateTime deletedAt, long version) {}

    public record DeletedMenuListResponse(List<DeletedMenuSummary> menus, String nextCursor, boolean hasNext) {}

    public record BulkPreview(List<BulkEntry> entries, int addCount, boolean hasInvalid) {}

    public record BulkEntry(int line, String name, String status) {}

    public record BulkCreateResult(int createdCount) {}

    public record MenuSummary(
            Long id,
            String name,
            int weight,
            boolean isExcluded,
            Set<String> categories,
            List<TagSummary> tags,
            /**
             * 이 시각까지 추천에서 쉰다. 쉬지 않으면 null이고, 지난 시각이면 이미 깨어난 것이다
             * — 화면은 {@code null}이 아니라 <b>지금과 비교</b>해서 "쉬는 중"을 판단해야 한다.
             */
            LocalDateTime pausedUntil
    ) {

        /** 쉬기 기능 이전의 6개 인자 호출부(테스트)용. */
        public MenuSummary(Long id, String name, int weight, boolean isExcluded,
                Set<String> categories, List<TagSummary> tags) {
            this(id, name, weight, isExcluded, categories, tags, null);
        }
    }

    public record MenuDetail(
            Long id,
            String name,
            String memo,
            int weight,
            boolean isExcluded,
            Set<String> categories,
            List<TagSummary> tags,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            /** 낙관적 락 버전. 수정 요청에 그대로 실어 보내야 한다 — 근거는 VersionGuard. */
            long version,
            /**
             * 이 시각까지 추천에서 쉰다. 쉬지 않으면 null이고, 지난 시각이면 이미 깨어난 것이다
             * — 화면은 {@code null}이 아니라 <b>지금과 비교</b>해서 "쉬는 중"을 판단해야 한다.
             */
            LocalDateTime pausedUntil
    ) {

        /** 쉬기 기능 이전의 10개 인자 호출부(테스트)용. */
        public MenuDetail(Long id, String name, String memo, int weight, boolean isExcluded,
                Set<String> categories, List<TagSummary> tags,
                LocalDateTime createdAt, LocalDateTime updatedAt, long version) {
            this(id, name, memo, weight, isExcluded, categories, tags,
                    createdAt, updatedAt, version, null);
        }
    }

    public record MenuListResponse(
            List<MenuSummary> menus,
            Long nextCursor,
            boolean hasNext
    ) {}

    public record TagSummary(Long id, String name) {}
}
