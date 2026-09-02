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

    /**
     * 기본은 자동완성(키워드 접두사 검색)이고, {@code ?all=true}면 내 태그 전체를 준다.
     *
     * <p>둘을 한 핸들러에 두는 이유는 같은 컬렉션의 같은 표현이기 때문이다. 다만 <b>기본값을
     * 바꾸지는 않는다</b> — 키워드 없는 요청이 전체를 주게 만들면 태그 입력창이 열리자마자
     * 제안 칩이 쏟아진다(자동완성은 지금 빈 키워드에 빈 목록을 받는 것에 의존한다).
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TagResponse.TagInfo>>> searchTags(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean all) {
        List<TagResponse.TagInfo> tags = all
                ? tagService.listTags(userId)
                : tagService.searchTags(userId, keyword);
        return ResponseEntity.ok(ApiResponse.ok(tags));
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
