package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 상황별 빠른 픽 — 저장형 프리셋({@code docs/PickPresetDesign.md}).
 *
 * <p>모든 경로가 인증 주체로 범위를 좁힌다. <b>{@code userId}를 요청에서 받지 않는다</b> —
 * 받으면 남의 프리셋을 읽거나 만들 수 있다. 남의 프리셋과 없는 프리셋은 같은 404다.
 */
@RestController
@RequestMapping("/api/v1/pick/presets")
@RequiredArgsConstructor
public class PickPresetController {

    private final PickPresetService pickPresetService;

    @GetMapping
    public ResponseEntity<ApiResponse<PickPresetResponse.ListResult>> list(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(pickPresetService.list(userId)));
    }

    @GetMapping("/{presetId}")
    public ResponseEntity<ApiResponse<PickPresetResponse.Detail>> get(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long presetId) {
        return ResponseEntity.ok(ApiResponse.ok(pickPresetService.get(userId, presetId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PickPresetResponse.Detail>> create(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid PickPresetRequest.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(pickPresetService.create(userId, request)));
    }

    /** 부분 수정이 아니라 <b>전체 교체</b>다 — 근거는 {@code PickPresetRequest.Update}. */
    @PutMapping("/{presetId}")
    public ResponseEntity<ApiResponse<PickPresetResponse.Detail>> update(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long presetId,
            @RequestBody @Valid PickPresetRequest.Update request) {
        return ResponseEntity.ok(ApiResponse.ok(pickPresetService.update(userId, presetId, request)));
    }

    /**
     * 삭제. 버전을 쿼리 파라미터로 받는다 — DELETE에 본문을 싣는 것은 프록시·클라이언트마다
     * 취급이 달라 기존 관례를 따른다.
     */
    @DeleteMapping("/{presetId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long presetId,
            @RequestParam @NotNull(message = "버전은 필수입니다.") Long version) {
        pickPresetService.delete(userId, presetId, version);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /**
     * 저장된 조건으로 즉시 픽한다.
     *
     * <p><b>필터 override를 받지 않는다.</b> 본문에는 버전과 (거리 조건이 있을 때만) 좌표만
     * 들어간다 — 조건을 덮어쓸 수 있게 하면 "저장해 둔 조건으로 뽑는다"는 약속이 깨지고,
     * 일반 {@code POST /pick}과 구분할 이유도 없어진다.
     */
    @PostMapping("/{presetId}/pick")
    public ResponseEntity<ApiResponse<PickPresetResponse.ExecutionResult>> execute(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long presetId,
            @RequestBody @Valid PickPresetRequest.Execute request) {
        return ResponseEntity.ok(ApiResponse.ok(pickPresetService.execute(userId, presetId, request)));
    }
}
