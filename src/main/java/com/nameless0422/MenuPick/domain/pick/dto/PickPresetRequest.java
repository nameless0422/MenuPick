package com.nameless0422.MenuPick.domain.pick.dto;

import com.nameless0422.MenuPick.domain.pick.PickPreset;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 프리셋 저장·실행 요청.
 *
 * <p><b>{@code userId}를 받지 않는다.</b> 소유자는 인증 주체에서만 얻는다 — 클라이언트가
 * 보낸 id를 믿으면 남의 프리셋을 만들거나 읽을 수 있다.
 *
 * <p><b>좌표를 저장 DTO에 두지 않는다.</b> 거리 기준점은 실행 요청에만 있고 DB에 남지 않는다.
 */
public final class PickPresetRequest {

    private PickPresetRequest() {
    }

    /**
     * 생성. 수정과 필드가 같지만 {@code version}과 승인 플래그가 없어 따로 둔다 —
     * 하나로 합치면 생성 요청에 버전을 넣을 수 있게 되고, 그 값이 무시된다는 사실을
     * 호출자가 알 방법이 없다.
     */
    public record Create(
            @NotBlank(message = "이름은 필수입니다.")
            @Size(max = PickPreset.NAME_MAX_LENGTH,
                    message = "이름은 " + PickPreset.NAME_MAX_LENGTH + "자 이하여야 합니다.")
            String name,

            @Size(max = 20, message = "카테고리는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotBlank(message = "카테고리는 비어 있을 수 없습니다.")
                @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.") String> categories,

            @Size(max = 20, message = "포함 태그는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotNull @Positive(message = "태그 ID는 양수여야 합니다.") Long> includeTagIds,

            /**
             * <b>추가</b> 제외만이다. 사용자의 기본 제외는 실행할 때마다 최신 값이 합쳐지므로
             * 여기 넣지 않는다. 넣어도 합집합이라 결과가 같지만, 넣어 두면 나중에 기본 제외를
             * 바꿔도 프리셋이 옛 값을 붙들고 있는 것처럼 보인다.
             */
            @Size(max = 20, message = "추가 제외 태그는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotNull @Positive(message = "태그 ID는 양수여야 합니다.") Long> additionalExcludeTagIds,

            /** {@code null}이면 거리 조건 없음. 값이 있으면 300/500/1000/2000만 허용한다. */
            Integer maxDistance
    ) {}

    /**
     * 수정 — <b>부분 수정이 아니라 전체 교체</b>다.
     *
     * <p>전체 교체인 이유는 검토 복구와 맞물려 있다. 태그가 지워진 프리셋을 되살리려면
     * 사용자가 남은 조건 전부를 다시 보고 승인해야 하고, 그 화면이 보내는 것이 곧 전체다.
     */
    public record Update(
            @NotBlank(message = "이름은 필수입니다.")
            @Size(max = PickPreset.NAME_MAX_LENGTH,
                    message = "이름은 " + PickPreset.NAME_MAX_LENGTH + "자 이하여야 합니다.")
            String name,

            @Size(max = 20, message = "카테고리는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotBlank(message = "카테고리는 비어 있을 수 없습니다.")
                @Size(max = 20, message = "카테고리는 20자 이하여야 합니다.") String> categories,

            @Size(max = 20, message = "포함 태그는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotNull @Positive(message = "태그 ID는 양수여야 합니다.") Long> includeTagIds,

            @Size(max = 20, message = "추가 제외 태그는 최대 20개까지 지정할 수 있습니다.")
            Set<@NotNull @Positive(message = "태그 ID는 양수여야 합니다.") Long> additionalExcludeTagIds,

            Integer maxDistance,

            /** 화면을 그릴 때 받은 값을 그대로 돌려보낸다 — 근거는 {@code VersionGuard}. */
            @NotNull(message = "버전은 필수입니다.")
            Long version,

            /**
             * 검토 필요 상태를 푸는 <b>명시적 승인</b>.
             *
             * <p>태그가 지워져 조건이 줄어든 프리셋은 이 값이 {@code true}여야만 되살아난다.
             * 없으면 409다 — 사용자가 "지금 조건이 이게 맞다"고 확인하지 않은 채로 다시
             * 돌기 시작하는 것을 막는다.
             */
            Boolean acknowledgeRemovedTags
    ) {}

    /**
     * 실행. <b>필터 override를 받지 않는다</b> — 저장된 조건 그대로 돈다.
     *
     * <p>좌표만 받는 이유는 그것만이 저장할 수 없는 값이기 때문이다(설계 6절).
     */
    public record Execute(
            @NotNull(message = "버전은 필수입니다.")
            Long version,

            @DecimalMin(value = "-90", message = "위도는 -90 이상이어야 합니다.")
            @DecimalMax(value = "90", message = "위도는 90 이하여야 합니다.")
            BigDecimal latitude,

            @DecimalMin(value = "-180", message = "경도는 -180 이상이어야 합니다.")
            @DecimalMax(value = "180", message = "경도는 180 이하여야 합니다.")
            BigDecimal longitude
    ) {}
}
