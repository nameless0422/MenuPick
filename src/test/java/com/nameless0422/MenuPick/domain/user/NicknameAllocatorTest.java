package com.nameless0422.MenuPick.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class NicknameAllocatorTest {

    @Mock private UserRepository userRepository;

    private NicknameAllocator allocator() {
        return new NicknameAllocator(userRepository);
    }

    /** 이미 쓰이는 이름 집합을 흉내 낸다. */
    private void taken(String... nicknames) {
        Set<String> used = Set.of(nicknames);
        given(userRepository.existsByNickname(org.mockito.ArgumentMatchers.anyString()))
                .willAnswer(inv -> used.contains(inv.<String>getArgument(0)));
    }

    @Test
    @DisplayName("비어 있는 이름은 그대로 쓴다")
    void freeNicknameIsKept() {
        taken();

        assertThat(allocator().allocate("홍길동")).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("이미 쓰이면 번호를 붙인다 — 소셜 로그인은 여기서 실패시킬 수 없다")
    void takenNicknameGetsSuffix() {
        taken("홍길동");

        // 사용자가 고른 적 없는 이름(제공자가 준 값)이라 거절하면 로그인 자체가 막힌다.
        assertThat(allocator().allocate("홍길동")).isEqualTo("홍길동2");
    }

    @Test
    @DisplayName("번호도 쓰이고 있으면 다음 번호로 넘어간다")
    void suffixSkipsTakenNumbers() {
        taken("메뉴픽 사용자", "메뉴픽 사용자2", "메뉴픽 사용자3");

        assertThat(allocator().allocate("메뉴픽 사용자")).isEqualTo("메뉴픽 사용자4");
    }

    @Test
    @DisplayName("번호를 붙여도 컬럼 길이(50자)를 넘지 않는다")
    void suffixNeverOverflowsColumn() {
        String fifty = "가".repeat(50);
        taken(fifty);

        String allocated = allocator().allocate(fifty);

        // 넘치면 저장 시점에 잘리거나 실패한다. 앞을 잘라내고 번호를 붙여야 한다.
        assertThat(allocated).hasSizeLessThanOrEqualTo(50);
        assertThat(allocated).isEqualTo("가".repeat(49) + "2");
    }

    @Test
    @DisplayName("탈퇴한 계정의 이름도 쓰이는 것으로 본다")
    void withdrawnAccountsStillHoldTheirName() {
        // existsByNickname은 deletedAt을 보지 않는다 — 유예기간 안에 돌아오면 쓰던 이름
        // 그대로 복구되어야 하므로 그동안 자리를 비워주면 안 된다.
        taken("돌아올사람");

        assertThat(allocator().allocate("돌아올사람")).isEqualTo("돌아올사람2");
    }
}
