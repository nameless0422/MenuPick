package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.tag.dto.TagResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TagResponse.TagInfo>>> searchTags(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false, defaultValue = "") String keyword) {
        return ResponseEntity.ok(ApiResponse.ok(tagService.searchTags(userId, keyword)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TagResponse.TagInfo>> createTag(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid TagRequest.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(tagService.createTag(userId, request)));
    }

    @DeleteMapping("/{tagId}")
    public ResponseEntity<ApiResponse<Void>> deleteTag(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long tagId) {
        tagService.deleteTag(userId, tagId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
