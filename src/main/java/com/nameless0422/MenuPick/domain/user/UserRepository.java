package com.nameless0422.MenuPick.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * 닉네임이 이미 쓰이고 있는지.
     *
     * <p>탈퇴(soft delete)한 계정도 포함해서 본다 — 유예기간 안에 돌아오면 쓰던 이름 그대로
     * 복구되어야 하므로 그동안은 자리를 비워주지 않는다. DB의 {@code uq_users_nickname}도
     * 같은 범위라 판정이 어긋나지 않는다.
     *
     * <p>비교는 컬럼 콜레이션(utf8mb4_unicode_ci)을 따라 대소문자를 구분하지 않는다.
     * 사칭을 막기 위한 것이며, 유니크 인덱스의 판정 기준과 같다.
     */
    boolean existsByNickname(String nickname);

    /**
     * 살아 있는 계정 중에 이 주소를 쓰는 계정이 있는지.
     *
     * <p>탈퇴(soft delete) 계정을 일부러 제외한다. 탈퇴한 사용자가 같은 주소로 다시 가입해
     * 돌아오는 경로가 있고({@code completeVerification}의 되살리기), 여기서 탈퇴 계정까지
     * "이미 가입됨"으로 막으면 그 길이 끊긴다 — 비밀번호 재설정도 탈퇴 계정은 대상에서
     * 빼므로 되돌아올 수단이 하나도 남지 않는다.
     */
    boolean existsByEmailAndDeletedAtIsNull(String email);

    List<User> findAllByDeletedAtBefore(LocalDateTime cutoff);
}
