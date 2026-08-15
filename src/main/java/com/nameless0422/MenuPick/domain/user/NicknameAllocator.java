package com.nameless0422.MenuPick.domain.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 비어 있는 닉네임을 찾아준다.
 *
 * <p>닉네임은 유일해야 한다(V5 마이그레이션). 자체 가입은 사용자가 직접 입력하므로 중복이면
 * 그대로 거절하면 되지만, <b>소셜 로그인은 거절할 수 없다</b> — 닉네임은 카카오·구글이 주는
 * 값이고 사용자가 고른 적이 없다. 흔한 이름이라는 이유로 예외를 던지면 그 사람은 로그인
 * 자체를 못 한다. 프로필 제공에 동의하지 않은 사용자는 전원 같은 기본값을 받으므로
 * 충돌은 예외적인 상황이 아니라 두 번째 사용자부터 곧바로 발생한다.
 *
 * <p>그래서 소셜 경로에서는 뒤에 번호를 붙여 빈 이름을 찾는다. 사용자가 "홍길동2"를 원했을
 * 리는 없지만, 로그인을 막는 것보다는 낫다.
 *
 * <p><b>경쟁 상태</b>: 조회와 저장 사이에 다른 트랜잭션이 같은 이름을 가져갈 수 있다.
 * 여기서 막지 않는다 — 마지막 방어선은 DB의 UNIQUE 제약이고, 소셜 로그인은
 * {@code AuthService}가 제약 위반 시 새 트랜잭션에서 한 번 더 시도한다. 재시도 때는
 * 상대가 커밋한 이름이 보이므로 다음 번호가 잡힌다.
 */
@Component
@RequiredArgsConstructor
public class NicknameAllocator {

    /** users.nickname 컬럼 길이. 접미사를 붙여도 이 길이를 넘지 않게 앞을 잘라낸다. */
    private static final int MAX_LENGTH = 50;

    /**
     * 번호를 붙여 볼 최대 횟수.
     *
     * <p>한 이름에 이만큼 몰릴 일은 사실상 없다. 그런데도 상한을 두는 이유는, 조회가 계속
     * 옛 스냅샷을 보는 상황에서 무한 루프에 빠지면 요청 스레드가 그대로 잠기기 때문이다.
     * 상한에 닿으면 아래에서 확실히 유일한 이름으로 빠져나간다.
     */
    private static final int MAX_ATTEMPTS = 50;

    private final UserRepository userRepository;

    /**
     * {@code desired}가 비어 있으면 그대로, 이미 쓰이고 있으면 번호를 붙인 이름을 준다.
     *
     * <p>탈퇴한 계정의 이름도 쓰이는 것으로 본다 — 유예기간 안에 돌아오면 쓰던 이름 그대로
     * 복구되어야 하기 때문이다. 유예가 지나면 하드 삭제 배치가 행을 지우면서 풀린다.
     */
    public String allocate(String desired) {
        if (!userRepository.existsByNickname(desired)) {
            return desired;
        }

        for (int suffix = 2; suffix <= MAX_ATTEMPTS; suffix++) {
            String candidate = withSuffix(desired, String.valueOf(suffix));
            if (!userRepository.existsByNickname(candidate)) {
                return candidate;
            }
        }

        // 여기까지 왔다면 번호로는 못 찾는 상황이다. UNIQUE 제약에 걸려 로그인을 실패시키느니
        // 충돌 가능성이 없는 이름을 만든다. 보기 좋지는 않지만 로그인은 통과한다.
        return withSuffix(desired, "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private String withSuffix(String base, String suffix) {
        int room = MAX_LENGTH - suffix.length();
        String trimmed = base.length() > room ? base.substring(0, room) : base;
        return trimmed + suffix;
    }
}
