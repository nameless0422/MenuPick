package com.nameless0422.MenuPick.common.domain;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;

/**
 * 클라이언트가 들고 있던 버전이 아직 최신인지 확인한다 (issue #87).
 *
 * <h2>@Version만으로는 왜 부족한가</h2>
 *
 * <p>엔티티의 {@code @Version}은 <b>겹쳐 있는 두 트랜잭션</b>을 막는다. 그런데 이 앱에서
 * 실제로 벌어지는 손실은 그 모양이 아니다 — 탭 하나가 10시에 편집 화면을 열고, 다른 탭이
 * 10시 5분에 저장하고, 처음 탭이 10시 6분에 저장한다. 세 트랜잭션은 서로 겹치지 않는다.
 * 마지막 요청이 도착했을 때 서버가 행을 새로 읽으면 버전도 이미 최신이라, DB 차원에서는
 * 아무 충돌도 없이 10시 5분의 변경이 조용히 덮인다.
 *
 * <p>그 사이의 공백을 메우는 유일한 방법은 <b>클라이언트가 자기가 본 버전을 함께 보내는
 * 것</b>이다. 이 클래스는 그 값을 서버가 방금 읽은 버전과 맞춰 본다.
 *
 * <h2>왜 버전을 필수로 받나</h2>
 *
 * <p>요청 DTO에서 이 필드는 {@code @NotNull}이다. 선택으로 두면 클라이언트가 값을 빼는
 * 것만으로 보호에서 조용히 빠져나갈 수 있는데, 그게 정확히 지금 고치려는 상태다.
 * 같은 이유로 {@code MenuRequest.Update.isExcluded}도 primitive가 아니라
 * {@code Boolean + @NotNull}이다 — 누락이 기본값으로 채워지는 대신 400이 되도록.
 */
public final class VersionGuard {

    private VersionGuard() {
    }

    /**
     * @param current   서버가 방금 읽은 엔티티의 버전
     * @param submitted 클라이언트가 화면을 그릴 때 받아 간 버전
     * @throws BusinessException 둘이 다르면 409 {@code CONCURRENT_MODIFICATION}.
     *                           "저장할 수 없는 값"이 아니라 "그 사이 누가 먼저 고쳤다"이므로,
     *                           사용자가 할 일은 입력을 고치는 것이 아니라 다시 불러오는 것이다.
     */
    public static void requireCurrentVersion(long current, Long submitted) {
        // submitted가 null인 경우는 @NotNull이 컨트롤러에서 이미 400으로 걸러 낸다.
        // 그래도 여기서 통과시키지 않는 이유는, 이 메서드가 검증 애너테이션이 붙지 않은
        // 경로에서 호출되더라도 "모르면 막는다"가 되어야 하기 때문이다.
        if (submitted == null || submitted != current) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }
}
