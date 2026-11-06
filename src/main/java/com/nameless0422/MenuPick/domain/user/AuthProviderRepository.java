package com.nameless0422.MenuPick.domain.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthProviderRepository extends JpaRepository<AuthProvider, Long> {

    Optional<AuthProvider> findByProviderAndSocialId(String provider, String socialId);

    Optional<AuthProvider> findByUserIdAndProvider(Long userId, String provider);

    List<AuthProvider> findAllByUserId(Long userId);

    /**
     * 주소로 계정 주인의 id만 조회한다. 엔티티를 영속성 컨텍스트에 올리지 않아,
     * 뒤따르는 잠금 조회가 옛 상태를 돌려받지 않는다.
     */
    @Query("select ap.user.id from AuthProvider ap where ap.provider = :provider and ap.socialId = :socialId")
    Optional<Long> findUserIdByProviderAndSocialId(@Param("provider") String provider,
                                                   @Param("socialId") String socialId);

    /**
     * 자격증명을 판정·변경하기 직전의 잠금 조회. 잠금 순서는 항상 users → auth_providers다.
     *
     * <p>InnoDB REPEATABLE READ에서 잠금 없는 조회는 트랜잭션 첫 읽기의 스냅샷을 보므로,
     * 앞선 동시 커밋을 놓친 비밀번호·버전으로 판단할 수 있다. 잠금 조회는 최신 커밋을 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ap from AuthProvider ap where ap.user.id = :userId and ap.provider = :provider")
    Optional<AuthProvider> findByUserIdAndProviderForUpdate(@Param("userId") Long userId,
                                                            @Param("provider") String provider);

    @Modifying
    @Query("delete from AuthProvider ap where ap.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
