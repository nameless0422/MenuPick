package com.nameless0422.MenuPick.domain.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoryRepository extends JpaRepository<History, Long> {

    List<History> findByUserIdOrderByRecommendedAtDesc(Long userId);
}
