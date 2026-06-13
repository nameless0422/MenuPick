package com.nameless0422.MenuPick.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthProviderRepository extends JpaRepository<AuthProvider, Long> {

    Optional<AuthProvider> findByProviderAndSocialId(String provider, String socialId);

    List<AuthProvider> findAllByUserId(Long userId);
}
