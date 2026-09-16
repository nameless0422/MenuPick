package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.history.dto.HistoryRequest;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;
    private final HistoryPlaceService historyPlaceService;

    @GetMapping
    public ResponseEntity<ApiResponse<HistoryResponse.HistoryListResponse>> getHistories(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) @Min(value = 1, message = "days는 1 이상이어야 합니다.") Integer days,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.getHistories(userId, cursor, days, size)));
    }

    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<HistoryResponse.VisitCalendarResponse>> getVisitCalendar(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String month) {
        return ResponseEntity.ok(ApiResponse.ok(historyService.getVisitCalendar(userId, month)));
    }

    @PatchMapping("/{historyId}/visit")
    public ResponseEntity<ApiResponse<Void>> markVisited(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long historyId,
            @RequestBody(required = false) HistoryRequest.VisitRequest request) {
        historyService.markVisited(userId, historyId,
                request != null ? request.restaurantId() : null);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /**
     * 뽑은 메뉴를 먹으러 갈 식당을 주변 검색 결과에서 고른다. 식당 저장·메뉴 연결·기록을
     * 한 번에 한다 — 근거는 {@link HistoryPlaceService}. 본문은 식당 저장 요청과 같은 모양이고
     * {@code kakaoPlaceId}가 반드시 있어야 한다.
     */
    @PostMapping("/{historyId}/place")
    public ResponseEntity<ApiResponse<HistoryResponse.PlaceChoiceResponse>> choosePlace(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long historyId,
            @Valid @RequestBody RestaurantRequest.Create request) {
        return ResponseEntity.ok(ApiResponse.ok(
                historyPlaceService.choosePlace(userId, historyId, request)));
    }

    @PatchMapping("/{historyId}/feedback")
    public ResponseEntity<ApiResponse<Void>> recordFeedback(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long historyId,
            @Valid @RequestBody HistoryRequest.FeedbackRequest request) {
        historyService.recordFeedback(userId, historyId, request.feedback());
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
