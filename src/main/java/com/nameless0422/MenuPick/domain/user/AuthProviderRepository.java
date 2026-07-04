package com.nameless0422.MenuPick.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuthProviderRepository extends JpaRepository<AuthProvider, Long> {

    Optional<AuthProvider> findByProviderAndSocialId(String provider, String socialId);

    List<AuthProvider> findAllByUserId(Long userId);

    @Modifying
    @Query("delete from AuthProvider ap where ap.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
