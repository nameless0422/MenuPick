package com.nameless0422.MenuPick.domain.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HistoryRepository extends JpaRepository<History, Long> {

    List<History> findByUserIdOrderByRecommendedAtDesc(Long userId);

    List<History> findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc(
            Long userId, LocalDateTime after, Long cursor, org.springframework.data.domain.Pageable pageable);

    List<History> findByUserIdAndRecommendedAtAfterOrderByIdDesc(
            Long userId, LocalDateTime after, org.springframework.data.domain.Pageable pageable);

    Optional<History> findByIdAndUserId(Long id, Long userId);
}
