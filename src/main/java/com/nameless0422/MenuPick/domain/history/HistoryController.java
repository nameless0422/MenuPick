package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping
    public ResponseEntity<ApiResponse<HistoryResponse.HistoryListResponse>> getHistories(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer days,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.getHistories(userId, cursor, days, size)));
    }

    @PatchMapping("/{historyId}/visit")
    public ResponseEntity<ApiResponse<Void>> markVisited(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long historyId) {
        historyService.markVisited(userId, historyId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @DeleteMapping("/{historyId}")
    public ResponseEntity<ApiResponse<Void>> deleteHistory(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long historyId) {
        historyService.deleteHistory(userId, historyId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
