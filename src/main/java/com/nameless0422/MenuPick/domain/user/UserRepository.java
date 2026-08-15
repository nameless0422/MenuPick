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

    List<User> findAllByDeletedAtBefore(LocalDateTime cutoff);
}
